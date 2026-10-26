"""17q G3.6 - đo luồng "AI lập checklist nghiệp vụ" trên API THẬT (ai-recruitment-agent, cổng 8082, KHÔNG có lỗi cài sẵn) với SRS thật
(A:/ai-recruitment-agent/docs/SRS.md). TỐN TOKEN, có trần cứng. Không có đáp án chuẩn: người đo gán nhãn thủ công sau đó.

Yêu cầu: project 'EXT - AI Recruitment Agent' đã import trong hệ thống; API thật chạy cổng 8082.
Chạy:  python scripts/eval-real-recruitment.py [--cap 80000]
Ghi docs/eval/real-recruitment-<ngày>.json (mọi mục AI lập + bước + token) và in chênh lệch sổ token CSDL."""
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

SRS = pathlib.Path("A:/ai-recruitment-agent/docs/SRS.md")
PROJECT = "0e11e87b-81a3-481c-9352-aad96f0c8cda"
ENDPOINTS = [
    ("POST", "/api/auth/register/hr"), ("POST", "/api/auth/register/candidate"), ("POST", "/api/auth/login"),
    ("POST", "/api/hr/jobs"), ("PUT", "/api/hr/jobs/{id}"), ("PATCH", "/api/hr/jobs/{id}/status"), ("PATCH", "/api/hr/jobs/{jobId}/blind-review"),
    ("POST", "/api/hr/companies"), ("PUT", "/api/hr/companies/{id}"),
    ("POST", "/api/hr/jobs/{jobId}/screening-questions"), ("PUT", "/api/hr/jobs/{jobId}/screening-questions/{questionId}"),
    ("POST", "/api/hr/jobs/{jobId}/rubric/criteria"), ("PUT", "/api/hr/jobs/{jobId}/rubric/criteria/{criterionId}"),
    ("PUT", "/api/hr/jobs/{jobId}/interview-template"), ("POST", "/api/hr/talent-pools"),
    ("POST", "/api/hr/applications/{applicationId}/interview-invitation"), ("PATCH", "/api/hr/applications/{applicationId}/status"),
    ("POST", "/api/candidates/applications"), ("PATCH", "/api/candidates/applications/{id}/withdraw"),
    ("PUT", "/api/candidates/profile/me"), ("POST", "/api/candidates/resumes"),
    ("POST", "/api/candidates/applications/{applicationId}/interview-invitation/confirm-slot"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--cap", type=int, default=80000)
    args = ap.parse_args()
    system = evalm.System(args.api, args.email, args.password)
    eps = system.endpoints(PROJECT)
    by_key = {(e["method"], e["path"]): e["id"] for e in eps}
    missing = [k for k in ENDPOINTS if k not in by_key]
    if missing:
        raise SystemExit(f"Endpoint không có trong project: {missing}")
    before = shopm.ledger_total()
    print("Sổ token TRƯỚC:", before)
    body, hdr = evalm.multipart({}, "SRS.md", SRS.read_bytes())
    _, doc = system.api.call("POST", f"/projects/{PROJECT}/requirement-documents", raw=body, headers=hdr)
    print("Đã tải SRS thật:", doc["data"]["fileName"], doc["data"]["sizeBytes"], "byte")
    t0 = time.time()
    job = system.generate(PROJECT, {"type": "RULES", "endpointIds": [by_key[k] for k in ENDPOINTS], "tokenCap": args.cap, "allowInferred": True, "suggest": True},
                          timeout=1800, progress=lambda j: print(f"    ... job {j['status']} {j['tokensUsed']} token"))
    seconds = round(time.time() - t0, 1)
    rules, page = [], 0
    while True:
        _, r = system.api.call("GET", f"/projects/{PROJECT}/business-rules?page={page}&size=200&status=PROPOSED")
        rules += r["data"]
        if len(r["data"]) < 200 or page > 20:
            break
        page += 1
    mine = [x for x in rules if x.get("extractionJobId") == job["id"]]
    after = shopm.ledger_total()
    print("Sổ token SAU:", after)
    print(f"job {job['status']}: {job['tokensUsed']}/{args.cap} token, {seconds}s, {len(mine)} mục mới; kết quả {job.get('rules')}")
    out = {"job": {"id": job["id"], "status": job["status"], "tokens": job["tokensUsed"], "cap": args.cap, "seconds": seconds, "extraction": job.get("rules"),
                   "steps": [{"phase": s["phase"], "status": s["status"], "detail": s["detail"], "tokens": s["tokens"]} for s in job["steps"]]},
           "tokenAccounting": {"before": before, "after": after, "eventTokens": after["eventTokens"] - before["eventTokens"], "jobTokens": after["jobTokens"] - before["jobTokens"]},
           "rules": [{"id": x["id"], "endpoint": x["endpointKey"], "template": x["template"], "params": x["params"], "sentence": x["sentence"], "source": x["source"],
                      "expectedStatus": x["expectedStatus"], "suggestedStatus": x.get("suggestedStatus"), "quote": x["quote"], "rationale": x["rationale"]} for x in mine]}
    path = REPO / "docs" / "eval" / f"real-recruitment-{datetime.date.today()}.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"TOKEN: chênh lệch sổ = {out['tokenAccounting']['eventTokens']}, job = {job['tokensUsed']}. Đã ghi {path}")


if __name__ == "__main__":
    sys.exit(main())
