#!/usr/bin/env python3
"""
Chấm một phiên E2 (người duyệt luật) sau khi người tham gia duyệt xong. 0 token, chỉ đọc.

Đầu vào: id project đã seed bằng `python scripts/seed-demo-rules.py --e2` (và người tham gia đã duyệt/loại luật trong tab Nghiệp vụ).
Đầu ra (in ra màn hình và ghi JSON nếu có --out):
  - ĐỘ CHÍNH XÁC: so quyết định cuối của từng luật với đáp án (luật SAI cố ý E2-W1, E2-W2 phải bị loại; mọi luật còn lại phải được duyệt).
    Đếm: quyết định đúng, duyệt nhầm luật sai (nguy hiểm nhất), loại nhầm luật đúng, chưa quyết định.
  - THỜI GIAN: từ số liệu đo trong giao diện (công tắc "Đo thời gian duyệt luật" phải BẬT trong lúc duyệt): số quyết định, tổng và trung vị giây,
    tách theo luật có/không có trích dẫn và theo quyết định. Dữ liệu đo ẩn danh, không có id luật nên thời gian được gộp, không gán cho từng luật.

Chạy:  python scripts/e2-score.py --project <id> [--email demo@aiapi.local --password demo12345] [--out docs/eval/e2-<người>.json]
"""
import argparse
import json
import statistics
import sys
import urllib.error
import urllib.request

WRONG_KEYS = {"E2-W1", "E2-W2"}


def call(base, token, path):
    req = urllib.request.Request(base + path, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def login(base, email, password):
    req = urllib.request.Request(base + "/auth/login", data=json.dumps({"email": email, "password": password}).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())["data"]["token"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    token = login(args.api, args.email, args.password)
    rules = call(args.api, token, f"/projects/{args.project}/business-rules?size=200")["data"]
    timing = call(args.api, token, f"/projects/{args.project}/business-rules/review-timing")["data"]

    rows, counts = [], {"đúng": 0, "duyệt nhầm luật sai": 0, "loại nhầm luật đúng": 0, "chưa quyết định": 0}
    for r in rules:
        should_reject = (r.get("rationale") or "") in WRONG_KEYS
        status = r["status"]
        if status == "APPROVED":
            verdict = "duyệt nhầm luật sai" if should_reject else "đúng"
        elif status == "REJECTED":
            verdict = "đúng" if should_reject else "loại nhầm luật đúng"
        else:
            verdict = "chưa quyết định"
        counts[verdict] += 1
        rows.append({"rule": r["sentence"], "hasQuote": r["quote"] is not None, "source": r["source"], "answer": "loại" if should_reject else "duyệt",
                     "status": status, "verdict": verdict})

    ms = [t["focusedMs"] for t in timing]

    def stat(sel):
        vals = [t["focusedMs"] for t in timing if sel(t)]
        return {"n": len(vals), "totalSeconds": round(sum(vals) / 1000, 1), "medianSeconds": round(statistics.median(vals) / 1000, 1) if vals else None}

    result = {"project": args.project, "rules": len(rules), "accuracy": counts,
              "timing": {"all": stat(lambda t: True), "withQuote": stat(lambda t: t["hadQuote"]), "withoutQuote": stat(lambda t: not t["hadQuote"]),
                         "approved": stat(lambda t: t["decision"] == "APPROVED"), "rejected": stat(lambda t: t["decision"] == "REJECTED"),
                         "edited": stat(lambda t: t["decision"] == "EDITED")}, "perRule": rows}
    print(f"Project {args.project}: {len(rules)} luật")
    for row in rows:
        print(f"  [{row['verdict']:<20}] đáp án {row['answer']:<5} | {row['status']:<9} | {'có trích dẫn' if row['hasQuote'] else 'không trích dẫn':<15} | {row['rule']}")
    print("\nĐỘ CHÍNH XÁC:", counts)
    print("THỜI GIAN (giây, từ công cụ đo trong giao diện):")
    if not ms:
        print("  (không có số liệu: công tắc đo thời gian chưa được bật trong lúc duyệt)")
    for k, v in result["timing"].items():
        print(f"  {k:<13} n={v['n']:<3} tổng={v['totalSeconds']:<8} trung vị={v['medianSeconds']}")
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print("Đã ghi", args.out)


if __name__ == "__main__":
    sys.exit(main())
