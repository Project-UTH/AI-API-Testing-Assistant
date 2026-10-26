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
PROJECT = ""  # tạo mới mỗi lần chạy (xem main)
TARGET = "http://localhost:8082"
PASSWORD = "Passw0rd!123"
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


def _call(path, body=None, tok=None):
    import urllib.request
    r = urllib.request.Request(TARGET + path, data=json.dumps(body).encode() if body is not None else None, method="POST" if body is not None else "GET",
                               headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + tok} if tok else {})})
    with urllib.request.urlopen(r, timeout=60) as x:
        return x.read()


def create_project(system):
    """Project mới trên API AI tuyển dụng THẬT: đăng nhập tài khoản HR thử (đã có công ty), import spec, đặt xác thực, tải SRS thật."""
    tok = json.loads(_call("/api/auth/login", {"email": "hr.thu@example.com", "password": PASSWORD}))["accessToken"]
    spec = _call("/v3/api-docs", tok=tok)
    pid = system.create_project("EVAL-REAL - API AI tuyển dụng thật", "Đo trên API thật của nhóm khác (scripts/eval-real-recruitment.py)")
    system.import_spec(pid, spec, "http://host.docker.internal:8082")
    system.set_target_auth(pid, tok)
    body, hdr = evalm.multipart({}, "SRS.md", SRS.read_bytes())
    system.api.call("POST", f"/projects/{pid}/requirement-documents", raw=body, headers=hdr)
    return pid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--cap", type=int, default=80000)
    ap.add_argument("--broad", action="store_true", help="gợi ý rộng")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    system = evalm.System(args.api, args.email, args.password)
    global PROJECT
    PROJECT = create_project(system)
    eps = system.endpoints(PROJECT)
    by_key = {(e["method"], e["path"]): e["id"] for e in eps}
    missing = [k for k in ENDPOINTS if k not in by_key]
    if missing:
        raise SystemExit(f"Endpoint không có trong project: {missing}")
    before = shopm.ledger_total()
    print("Sổ token TRƯỚC:", before)
    print("Project mới:", PROJECT, "(SRS thật đã tải)")
    t0 = time.time()
    job = system.generate(PROJECT, {"type": "RULES", "endpointIds": [by_key[k] for k in ENDPOINTS], "tokenCap": args.cap, "allowInferred": True, "suggest": True, "suggestBroad": args.broad},
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
    path = pathlib.Path(args.out) if args.out else REPO / "docs" / "eval" / "v2" / f"real-recruitment-{datetime.datetime.now():%Y%m%d-%H%M%S}.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"TOKEN: chênh lệch sổ = {out['tokenAccounting']['eventTokens']}, job = {job['tokensUsed']}. Đã ghi {path}")


if __name__ == "__main__":
    sys.exit(main())
