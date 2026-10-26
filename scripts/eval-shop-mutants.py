"""Đo khả năng bắt lỗi của checklist TẤT ĐỊNH (cấu hình A, 0 token) trên hệ thống thứ HAI: API cửa hàng có lỗi cài sẵn (mutant-shop).

Dùng lại nguyên khung đo của eval-mutants.py (cùng cách chạy bản chuẩn, lượt đối chứng, phân loại caught/suspect/flagged/missed) nên số đo
của 2 hệ thống so sánh được. Khác biệt duy nhất là API đích, test gốc do người viết (7 test, dưới đây) và không có ma trận phân quyền
(chỉ 1 vai).

Sổ token: đếm tổng token trong CSDL (test_generation_events + generation_jobs) TRƯỚC và SAU; cấu hình A phải ra chênh lệch 0.

Chạy:
  cd mutant-shop && MUTANT_CONTROL=true java -jar target/mutant-shop-0.0.1-SNAPSHOT.jar      (cổng 8084; MySQL của shop ở cổng 3307)
  python scripts/eval-shop-mutants.py --runs 2
"""
import argparse
import datetime
import importlib.util
import json
import pathlib
import subprocess
import sys
import time
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("evalm", HERE / "eval-mutants.py")
evalm = importlib.util.module_from_spec(spec)
sys.modules["evalm"] = evalm
spec.loader.exec_module(evalm)

REPO = HERE.parent


class ShopTarget(evalm.Target):
    """Cùng giao thức điều khiển (/__mutants, /__reset); khác cách đăng nhập và lấy spec (spec của shop cần Bearer)."""

    def __init__(self, local):
        super().__init__(local)
        self._token = None

    def token(self):
        if not self._token:
            _, body = self.api.call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
            self._token = body["token"]
        return self._token

    def spec(self):
        req = urllib.request.Request(self.api.base + "/v3/api-docs", headers={"Authorization": "Bearer " + self.token()})
        return urllib.request.urlopen(req, timeout=30).read()


# Test gốc do NGƯỜI viết (giống cách đo hệ thống thứ nhất): mỗi endpoint một test hợp lệ, chạy xác nhận Pass trên bản chuẩn.
BASELINES = [
    {"key": "L1", "name": "L1 Đăng nhập hợp lệ", "method": "POST", "specPath": "/auth/login", "path": "/auth/login", "expected": 200,
     "auth": "NONE", "body": {"username": "admin", "password": "admin123"}},
    {"key": "P1", "name": "P1 Tạo sản phẩm hợp lệ", "method": "POST", "specPath": "/api/products", "path": "/api/products", "expected": 201,
     "body": {"name": "Ao thun", "description": "mau trang", "price": 120000, "stock": 10}},
    {"key": "P2", "name": "P2 Xem sản phẩm vừa tạo", "method": "GET", "specPath": "/api/products/{id}", "path": "/api/products/{{id}}", "expected": 200,
     "dep": {"from": "P1", "jsonPath": "$.id", "placeholder": "id"}},
    {"key": "P3", "name": "P3 Danh sách sản phẩm", "method": "GET", "specPath": "/api/products", "path": "/api/products", "expected": 200},
    {"key": "P4", "name": "P4 Cập nhật sản phẩm", "method": "PUT", "specPath": "/api/products/{id}", "path": "/api/products/{{id}}", "expected": 200,
     "body": {"name": "Ao thun moi", "description": "mau den", "price": 150000, "stock": 8}, "dep": {"from": "P1", "jsonPath": "$.id", "placeholder": "id"}},
    {"key": "P5", "name": "P5 Tăng tồn kho", "method": "PATCH", "specPath": "/api/products/{id}/stock",
     "path": "/api/products/{{id}}/stock?quantity=2&operation=increase", "expected": 200,
     "dep": {"from": "P1", "jsonPath": "$.id", "placeholder": "id"}},
    {"key": "P6", "name": "P6 Xoá sản phẩm", "method": "DELETE", "specPath": "/api/products/{id}", "path": "/api/products/{{id}}", "expected": 204,
     "dep": {"from": "P1", "jsonPath": "$.id", "placeholder": "id"}},
]


def create_baselines(system, pid, endpoint_id):
    ids = {}
    for b in BASELINES:
        payload = {"name": b["name"], "expectedStatus": b["expected"], "resolvedPath": b["path"],
                   "authOverride": b.get("auth", "DEFAULT"), "assertions": []}
        if b.get("body") is not None:
            payload["requestBody"] = json.dumps(b["body"])
            payload["requestHeaders"] = '{"Content-Type":"application/json"}'
        if "{{" in b["path"]:
            payload["pathParamFallbacks"] = json.dumps({b["dep"]["placeholder"]: "987654"})
        if b.get("dep"):
            payload["dependencies"] = [{"dependsOnTestCaseId": ids[b["dep"]["from"]], "jsonPath": b["dep"]["jsonPath"],
                                        "placeholderName": b["dep"]["placeholder"]}]
        ids[b["key"]] = system.create_test(pid, endpoint_id[(b["method"], b["specPath"])], payload)
    return ids


def ledger_total():
    """Tổng token trong CSDL của hệ thống chính (mọi người dùng): sổ sinh test + token của các job. Dùng để chứng minh số token đã dùng."""
    sql = ("select (select coalesce(sum(total_tokens),0) from test_generation_events), (select coalesce(sum(tokens_used),0) from generation_jobs), "
           "(select count(*) from test_generation_events), (select count(*) from generation_jobs)")
    out = subprocess.run(["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
                          f'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "{sql}" $MYSQL_DATABASE'],
                         cwd=REPO, capture_output=True, text=True, timeout=60, stdin=subprocess.DEVNULL).stdout.strip().splitlines()[-1].split()
    return {"eventTokens": int(out[0]), "jobTokens": int(out[1]), "eventRows": int(out[2]), "jobRows": int(out[3])}


def run_once(args, run_no, target, system, mutants, log):
    stamp = datetime.datetime.now().strftime("%m%d-%H%M%S")
    target.reset()
    token = target.token()
    pid = system.create_project(f"EVAL-SHOP-A-{run_no}-{stamp}", "Đo khả năng bắt lỗi trên hệ thống thứ hai (scripts/eval-shop-mutants.py)")
    system.import_spec(pid, target.spec(), args.target_url)
    system.set_target_auth(pid, token)
    if args.unauthenticated_status:
        # quy ước của API đích do người dùng khai (Project.unauthenticatedStatus): API này trả 403 khi thiếu/sai token
        system.api.call("PUT", f"/projects/{pid}/api-conventions", {"unauthenticatedStatus": args.unauthenticated_status})
    eps = system.endpoints(pid)
    endpoint_id = {(e["method"], e["path"]): e["id"] for e in eps}
    log(f"  project {pid}: {len(eps)} endpoint đã import")
    ids = create_baselines(system, pid, endpoint_id)
    base_results = system.run(pid, list(ids.values()))
    bad = [r["testCaseName"] for r in base_results if r["status"] != "PASSED"]
    if bad:
        raise SystemExit(f"Test gốc không Pass trên bản chuẩn (lỗi fixture): {bad}")
    log(f"  {len(ids)} test gốc do người viết: tất cả Pass")

    body = {"endpointIds": [e["id"] for e in eps], "type": "SYSTEM", "confirmWrite": True, "includePositive": True, "includeNegative": True,
            "includeBoundary": True, "includeSecurity": True, "includeOwasp": True}
    t0 = time.time()
    job = system.generate(pid, body, timeout=900)
    seconds = round(time.time() - t0, 1)
    gen = {"jobStatus": job["status"], "tokens": job["tokensUsed"], "seconds": seconds,
           "endpointResults": [{"label": r["label"], "status": r["status"], "reason": r.get("reason"), "tokens": r["tokens"]} for r in job.get("results", [])]}
    log(f"  sinh test (tầng hệ thống) xong: {job['status']}, {job['tokensUsed']} token, {seconds}s")

    tests = system.tests(pid)
    added = 0
    for eid in endpoint_id.values():  # cùng phần 'Bù coverage' từng endpoint như hệ thống thứ nhất
        system.api.call("PUT", f"/projects/{pid}/endpoints/{eid}/coverage/scope",
                        {"positive": True, "negative": True, "boundary": True, "security": True, "owasp": True}, expect_ok=False)
        status, r = system.api.call("POST", f"/projects/{pid}/endpoints/{eid}/coverage/fill", {}, expect_ok=False)
        if status < 300 and isinstance(r, dict) and isinstance((r.get("data") or {}).get("created"), list):
            added += len(r["data"]["created"])
    after = system.tests(pid)
    log(f"  Bù coverage: {len(tests)} -> {len(after)} test (+{added} báo cáo)")
    tests = after
    origins = {}
    for t in tests:
        k = (t.get("generationOrigin") or t.get("source")) + (f"/{t['probeKind']}" if t.get("probeKind") else "")
        origins[k] = origins.get(k, 0) + 1
    out = {"run": run_no, "projectId": pid, "generation": gen, "testsByOrigin": origins, "configs": {}}
    out["configs"]["A"] = evalm.evaluate(system, target, pid, tests, mutants, "A", log)
    if not args.keep_projects:
        system.delete_project(pid)
    return out


def main():
    ap = argparse.ArgumentParser(description="Đo cấu hình A (0 token) trên mutant-shop")
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8084")
    ap.add_argument("--target-url", default="http://host.docker.internal:8084")
    ap.add_argument("--email", default="eval@aiapi.local")
    ap.add_argument("--password", default="eval12345")
    ap.add_argument("--model", default="(không dùng AI)")
    ap.add_argument("--unauthenticated-status", type=int, default=0, help="khai quy ước API: mã trả khi thiếu/sai token (403 cho API này); 0 = không khai (401 chuẩn)")
    ap.add_argument("--keep-projects", action="store_true")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    log = lambda msg: print(msg, flush=True)

    target = ShopTarget(args.target_local)
    mutants = target.catalog()
    system = evalm.System(args.api, args.email, args.password)
    before = ledger_total()
    log(f"Sổ token TRƯỚC: {before}")
    runs = []
    for n in range(1, args.runs + 1):
        log(f"\n--- Lượt {n}/{args.runs} - cấu hình A (0 token), {len(mutants)} mutant ---")
        runs.append(run_once(args, n, target, system, mutants, log))
    after = ledger_total()
    log(f"Sổ token SAU: {after}")
    accounting = {"before": before, "after": after,
                  "eventTokensUsed": after["eventTokens"] - before["eventTokens"], "jobTokensUsed": after["jobTokens"] - before["jobTokens"],
                  "newEventRows": after["eventRows"] - before["eventRows"]}
    result = {"environment": evalm.environment_info(args), "mutantCatalog": mutants, "tokenAccounting": accounting, "runs": runs, "summary": {}}
    s = evalm.summarize(runs, "A")
    result["summary"]["A"] = s
    evalm.print_summary("A", s, runs)
    log(f"\nTOKEN ĐÃ DÙNG (chênh lệch sổ trước/sau): sinh test+AI = {accounting['eventTokensUsed']}, token của job = {accounting['jobTokensUsed']}")
    out = pathlib.Path(args.out) if args.out else REPO / "docs" / "eval" / f"results-shop-A-{datetime.date.today()}.json"
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nĐã ghi kết quả: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
