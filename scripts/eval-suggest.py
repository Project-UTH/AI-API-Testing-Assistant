"""17q G3.6 - đo làn "AI gợi ý thêm" (GIẢ ĐỊNH) trên mutant-api. TỐN TOKEN, có trần cứng mỗi lượt.

  S1 (chỉ spec): không tài liệu, không chỉ dẫn -> làn gợi ý tự bật. Trần mặc định 25.000.
  S2 (tài liệu + gợi ý): tải SRS, bật suggest -> làn tài liệu rồi làn gợi ý. Trần mặc định 40.000.

Mỗi lượt: project mới + 10 test gốc người viết (đã Pass) + job RULES. Ghi mọi mục AI lập ra docs/eval/suggest-<ngày>.json (kèm suggestedStatus)
để người đo gán nhãn bằng docs/eval/srs-mutant-api.answer-key.md. In số token của job và chênh lệch sổ CSDL trước/sau.

Chạy:  python scripts/eval-suggest.py [--cap1 25000] [--cap2 40000]
"""
import argparse
import datetime
import importlib.util
import json
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


evalm = load("evalm", "eval-mutants.py")
shopm = load("shopm", "eval-shop-mutants.py")
SRS = REPO / "docs" / "eval" / "srs-mutant-api.md"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8083")
    ap.add_argument("--target-url", default="http://host.docker.internal:8083")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--model", default="")
    ap.add_argument("--cap1", type=int, default=25000)
    ap.add_argument("--cap2", type=int, default=40000)
    args = ap.parse_args()

    target = evalm.Target(args.target_local)
    target.catalog()
    system = evalm.System(args.api, args.email, args.password)
    before = shopm.ledger_total()
    print("Sổ token TRƯỚC:", before)
    runs = []
    for name, with_doc, cap in (("S1-chi-spec", False, args.cap1), ("S2-tai-lieu-va-goi-y", True, args.cap2)):
        stamp = datetime.datetime.now().strftime("%m%d-%H%M%S")
        target.reset()
        hr, cand = target.login("hr1@demo.local"), target.login("cand1@demo.local")
        pid = system.create_project(f"EVAL-SUGGEST-{name}-{stamp}", "Đo làn AI gợi ý thêm (scripts/eval-suggest.py)")
        system.import_spec(pid, target.spec(), args.target_url)
        system.set_target_auth(pid, hr)
        cand_profile = system.create_profile(pid, "CANDIDATE", cand)
        system.create_profile(pid, "HR", hr)
        eps = system.endpoints(pid)
        endpoint_id = {(e["method"], e["path"]): e["id"] for e in eps}
        ids = evalm.create_baselines(system, pid, endpoint_id, cand_profile)
        bad = [r["testCaseName"] for r in system.run(pid, list(ids.values())) if r["status"] != "PASSED"]
        if bad:
            raise SystemExit(f"Test gốc không Pass trên bản chuẩn: {bad}")
        if with_doc:
            body, hdr = evalm.multipart({}, "srs-mutant-api.md", SRS.read_bytes())
            system.api.call("POST", f"/projects/{pid}/requirement-documents", raw=body, headers=hdr)
        t0 = time.time()
        job = system.generate(pid, {"type": "RULES", "endpointIds": [e["id"] for e in eps], "tokenCap": cap, "allowInferred": True, "suggest": True},
                              timeout=1800, progress=lambda j: print(f"    ... job {j['status']} {j['tokensUsed']} token"))
        seconds = round(time.time() - t0, 1)
        rules, page = [], 0
        while True:
            _, r = system.api.call("GET", f"/projects/{pid}/business-rules?page={page}&size=200")
            rules += r["data"]
            if len(r["data"]) < 200 or page > 20:
                break
            page += 1
        compact = [{"id": x["id"], "endpoint": x["endpointKey"], "template": x["template"], "params": x["params"], "sentence": x["sentence"],
                    "source": x["source"], "status": x["status"], "expectedStatus": x["expectedStatus"], "suggestedStatus": x.get("suggestedStatus"),
                    "hasQuote": x["quote"] is not None, "rationale": x["rationale"]} for x in rules]
        print(f"{name}: job {job['status']}, {job['tokensUsed']}/{cap} token, {seconds}s, {len(rules)} mục; kết quả {job.get('rules')}")
        runs.append({"scenario": name, "projectId": pid, "jobId": job["id"], "jobStatus": job["status"], "tokens": job["tokensUsed"], "cap": cap,
                     "seconds": seconds, "extraction": job.get("rules"),
                     "steps": [{"phase": s["phase"], "status": s["status"], "detail": s["detail"], "tokens": s["tokens"]} for s in job["steps"]],
                     "rules": compact})
    after = shopm.ledger_total()
    print("Sổ token SAU:", after)
    out = {"environment": evalm.environment_info(args), "tokenAccounting": {
        "before": before, "after": after, "eventTokens": after["eventTokens"] - before["eventTokens"],
        "jobTokens": after["jobTokens"] - before["jobTokens"]}, "runs": runs}
    path = REPO / "docs" / "eval" / f"suggest-{datetime.date.today()}.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"TOKEN: chênh lệch sổ = {out['tokenAccounting']['eventTokens']}, tổng job = {sum(r['tokens'] for r in runs)}. Đã ghi {path}")


if __name__ == "__main__":
    sys.exit(main())
