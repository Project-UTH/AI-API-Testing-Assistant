#!/usr/bin/env python3
"""
Seed project demo cho luồng "tài liệu nghiệp vụ -> luật -> duyệt -> test từ luật -> bắt lỗi" (17q) trên API tuyển dụng `mutant-api`.

Chế độ mặc định: 0 TOKEN. Dựng project, import spec, 10 test gốc do người viết (đã chạy xác nhận Pass), tải SRS, rồi tạo 5 luật mà AI đã trích
trong đợt đo G5 (docs/eval/rules-extract-2026-10-26.json) dưới dạng luật NHẬP TAY ở trạng thái "Chờ duyệt". Không có đoạn trích tô sáng
(luật nhập tay không có nguồn tài liệu).

Chế độ --with-ai: gọi AI THẬT 1 lần để trích luật từ SRS (≈ 14.000 token đo thật, trần cứng 25.000), nên luật có trích dẫn nguyên văn và nút
"Xem trong tài liệu" tô sáng. LÀM TRƯỚC GIỜ DEMO, không làm trên sân khấu. Script in số token đã dùng.

Yêu cầu: hệ thống chạy (docker compose up), mutant-api chạy cổng 8083 với MUTANT_CONTROL=true.
Chạy:  python scripts/seed-demo-rules.py [--with-ai] [--email demo@aiapi.local --password demo12345]
"""
import argparse
import importlib.util
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
spec = importlib.util.spec_from_file_location("evalm", HERE / "eval-mutants.py")
evalm = importlib.util.module_from_spec(spec)
sys.modules["evalm"] = evalm
spec.loader.exec_module(evalm)

SRS = REPO / "docs" / "eval" / "srs-mutant-api.md"
EXTRACT = REPO / "docs" / "eval" / "rules-extract-2026-10-26.json"


# Luật thêm cho phép đo E2 (nhập tay, KHÔNG có trích dẫn). Đáp án: C1, C2 đúng (có trong tài liệu chuẩn: tối đa 20 tiêu chí, ít nhất 1 tiêu chí);
# W1, W2 SAI cố ý (W1: tài liệu không nêu trần lương; W2: tài liệu nói hạn nộp "hôm nay vẫn được chấp nhận", không phải 30 ngày sau).
E2_RULES = [
    ("C1", True, "PUT", "/api/jobs/{id}/rubric", "ARRAY_AGGREGATE", {"array": "criteria", "fn": "COUNT", "op": "LTE", "value": 20}),
    ("C2", True, "PUT", "/api/jobs/{id}/rubric", "ARRAY_AGGREGATE", {"array": "criteria", "fn": "COUNT", "op": "GTE", "value": 1}),
    ("W1", False, "POST", "/api/jobs", "VALUE_RANGE", {"field": "salaryMax", "max": 5000}),
    ("W2", False, "POST", "/api/jobs", "DATE_RELATIVE", {"field": "deadline", "op": "GTE", "offset": 30, "unit": "DAY"}),
]


def add_e2_rules(system, pid, endpoint_id):
    print("ĐÁP ÁN E2 (không đưa cho người tham gia):")
    for key, correct, method, path, template, params in E2_RULES:
        _, r = system.api.call("POST", f"/projects/{pid}/business-rules",
                               {"endpointId": endpoint_id[(method, path)], "template": template, "params": params, "expectedStatus": None,
                                "rationale": None, "approve": False})  # ghi chú để trống: không để lộ mã/đáp án trên giao diện
        print(f"  {key}: {'ĐÚNG (nên duyệt)' if correct else 'SAI (nên loại)'}  {r['data']['sentence']}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-ai", action="store_true", help="gọi AI thật để trích luật (≈ 14.000 token)")
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8083")
    ap.add_argument("--target-url", default="http://host.docker.internal:8083")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--name", default="Demo - Luật nghiệp vụ (API tuyển dụng)")
    ap.add_argument("--token-cap", type=int, default=25000)
    ap.add_argument("--e2", action="store_true", help="thêm 4 luật nhập tay (2 đúng, 2 SAI cố ý) để đo người duyệt có bắt được luật sai không (docs/eval/E2-do-thoi-gian-duyet.md)")
    args = ap.parse_args()

    target = evalm.Target(args.target_local)
    target.catalog()
    system = evalm.System(args.api, args.email, args.password)
    target.reset()
    hr, cand = target.login("hr1@demo.local"), target.login("cand1@demo.local")
    pid = system.create_project(args.name, "Demo luồng tài liệu nghiệp vụ -> luật -> test (scripts/seed-demo-rules.py)")
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
    print(f"project {pid}: {len(eps)} endpoint, {len(ids)} test gốc đều Pass")

    body, hdr = evalm.multipart({}, "srs-mutant-api.md", SRS.read_bytes())
    system.api.call("POST", f"/projects/{pid}/requirement-documents", raw=body, headers=hdr)
    print("đã tải tài liệu srs-mutant-api.md")

    if args.with_ai:
        job = system.generate(pid, {"type": "RULES", "endpointIds": [e["id"] for e in eps], "tokenCap": args.token_cap, "allowInferred": True}, timeout=1800)
        print(f"AI trích luật: {job['status']}, {job['tokensUsed']} token, kết quả {job.get('rules')}")
    else:
        rules = json.loads(EXTRACT.read_text(encoding="utf-8"))["runs"][0]["rules"]
        for r in rules:
            method, path = r["endpoint"].split(" ", 1)
            system.api.call("POST", f"/projects/{pid}/business-rules",
                            {"endpointId": endpoint_id[(method, path)], "template": r["template"], "params": r["params"],
                             "expectedStatus": r["expectedStatus"], "rationale": None if args.e2 else "Luật đã được AI trích trong đợt đo (dựng lại, 0 token)", "approve": False})
        print(f"đã tạo {len(rules)} luật chờ duyệt (0 token)")
    if args.e2:
        add_e2_rules(system, pid, endpoint_id)
    target.reset()
    print(f"\nXong. Mở http://localhost:3000/projects/{pid}/rules (tab Nghiệp vụ). Đăng nhập bằng {args.email}.")
    print("Bật lỗi để bắt khi demo:  curl -X POST localhost:8083/__mutants -H 'Content-Type: application/json' -d '{\"enabled\":[\"M23\"]}'   (tắt: POST /__reset)")


if __name__ == "__main__":
    main()
