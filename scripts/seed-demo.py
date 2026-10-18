#!/usr/bin/env python3
"""
Seed dữ liệu demo cho AI API Testing Assistant - KHÔNG gọi AI (không tốn token), không chèn SQL: chỉ gọi REST API của
chính hệ thống, nên dữ liệu sinh ra đúng như người dùng thật tạo ra.

Việc script làm (trên tài khoản demo RIÊNG, không đụng tài khoản test dùng chung):
  1. đăng ký/đăng nhập tài khoản demo
  2. tạo project "Demo - Shop API", import OpenAPI của shop-api-target, đặt Bearer token
  3. tạo 25 test case THỦ CÔNG (đủ loại: chuỗi dữ liệu, negative/boundary, lỗi thật của target, 404 do id dự phòng,
     lệch mã 401/403, test không ổn định)
  4. chạy 7 lượt (lượt 4 dùng token sai để có ví dụ lỗi xác thực), rồi sinh bug report hàng loạt (tất định)

Yêu cầu: hệ thống đang chạy (docker compose up) và shop-api-target đang chạy ở cổng 8081.
Chạy:   python scripts/seed-demo.py            (thêm --recreate để xoá project demo cũ rồi seed lại)
Chỉ dùng thư viện chuẩn của Python 3.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

PROJECT_NAME = "Demo - Shop API"
UNKNOWN_ID = "987654"  # id dự phòng chắc chắn không tồn tại (IDENTITY của target chỉ tăng)
JSON_HEADERS = '{"Content-Type":"application/json"}'
VALID_BODY = '{"name":"Demo Laptop","description":"d","price":1500,"stock":10}'


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method, path, body=None, raw=None, headers=None, expect_ok=True):
        h = {} if raw is not None else {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if headers:
            h.update(headers)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read().decode()
                status = r.status
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            status = e.code
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = text
        if expect_ok and status >= 400:
            raise SystemExit(f"[LỖI] {method} {path} -> {status}: {str(parsed)[:300]}")
        return status, parsed


def multipart(fields, filename, content):
    boundary = uuid.uuid4().hex
    out = b""
    for k, v in fields.items():
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    out += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f'Content-Type: application/json\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return out, {"Content-Type": "multipart/form-data; boundary=" + boundary}


# (khóa, tên, method, path-spec, resolvedPath, expected, authOverride, body, nguồn dependency, fallback id, assertions)
def case_table():
    P = "/api/products"
    S = "/api/products/{id}"
    ST = "/api/products/{id}/stock"
    chain = "P1"
    return [
        ("P1", "P1 Tạo sản phẩm hợp lệ", "POST", P, P, 201, "DEFAULT", VALID_BODY, None, False, []),
        ("P2", "P2 Lấy sản phẩm vừa tạo", "GET", S, P + "/{{id}}", 200, "DEFAULT", None, chain, True, []),
        ("P3", "P3 Cập nhật sản phẩm vừa tạo", "PUT", S, P + "/{{id}}", 200, "DEFAULT",
         '{"name":"Demo Laptop v2","description":"d","price":1600,"stock":8}', chain, True, []),
        ("P4", "P4 Tăng tồn kho +5", "PATCH", ST, P + "/{{id}}/stock?quantity=5&operation=increase", 200, "DEFAULT", None, chain, True, []),
        ("P5", "P5 Xoá sản phẩm vừa tạo", "DELETE", S, P + "/{{id}}", 204, "DEFAULT", None, chain, True, []),
        ("P6", "P6 Danh sách sản phẩm", "GET", P, P, 200, "DEFAULT", None, None, False, []),
        ("P7", "P7 Lọc theo tên", "GET", P, P + "?name=laptop", 200, "DEFAULT", None, None, False, []),
        ("N1", "N1 Thiếu trường name", "POST", P, P, 400, "DEFAULT", '{"price":10,"stock":1}', None, False, []),
        ("N2", "N2 price âm", "POST", P, P, 400, "DEFAULT", '{"name":"x","price":-1,"stock":1}', None, False, []),
        ("N3", "N3 id không phải số", "GET", S, P + "/abc", 400, "DEFAULT", None, None, False, []),
        ("N4", "N4 minPrice không phải số", "GET", P, P + "?minPrice=abc", 400, "DEFAULT", None, None, False, []),
        ("B1", "B1 price = 0 (biên hợp lệ)", "POST", P, P, 201, "DEFAULT", '{"name":"x","price":0,"stock":0}', None, False, []),
        # Lỗi thật của target: spec nói quantity > 0 và operation chỉ nhận increase/decrease nhưng target trả 200.
        ("R1", "R1 quantity = 0 (spec: phải > 0)", "PATCH", ST, P + "/{{id}}/stock?quantity=0&operation=increase", 400, "DEFAULT", None, chain, True, []),
        ("R2", "R2 quantity âm", "PATCH", ST, P + "/{{id}}/stock?quantity=-5&operation=increase", 400, "DEFAULT", None, chain, True, []),
        ("R3", "R3 operation không hợp lệ", "PATCH", ST, P + "/{{id}}/stock?quantity=1&operation=invalid_op", 400, "DEFAULT", None, chain, True, []),
        # Target trả 403 (không phải 400) khi JSON sai kiểu / thiếu tham số bắt buộc: bug thật của target.
        ("R4", "R4 price sai kiểu (chuỗi)", "POST", P, P, 400, "DEFAULT", '{"name":"x","price":"abc","stock":1}', None, False, []),
        ("R5", "R5 thiếu quantity", "PATCH", ST, P + "/{{id}}/stock?operation=increase", 400, "DEFAULT", None, chain, True, []),
        ("R6", "R6 stock vượt int32", "POST", P, P, 400, "DEFAULT", '{"name":"x","price":1,"stock":2147483648}', None, False, []),
        # id dự phòng không tồn tại -> RESOURCE_NOT_FOUND (test chưa chạm tới logic cần kiểm).
        ("F1", "F1 GET bằng id dự phòng", "GET", S, P + "/{{id}}", 200, "DEFAULT", None, None, True, []),
        ("F2", "F2 PUT bằng id dự phòng", "PUT", S, P + "/{{id}}", 200, "DEFAULT",
         '{"name":"x","description":"d","price":1,"stock":1}', None, True, []),
        ("F3", "F3 Negative bị chặn bởi 404", "PATCH", ST, P + "/{{id}}/stock?quantity=-3&operation=increase", 400, "DEFAULT", None, None, True, []),
        # Lệch mã nhưng cùng nhóm nghĩa -> EXPECTATION_MISMATCH.
        ("E1", "E1 Bỏ token, kỳ vọng 401", "GET", P, P, 401, "NONE", None, None, False, []),
        ("E2", "E2 Token sai, kỳ vọng 401", "POST", P, P, 401, "INVALID", VALID_BODY, None, False, []),
        ("E3", "E3 Tạo mới kỳ vọng 200 (spec: 201)", "POST", P, P, 200, "DEFAULT", VALID_BODY, None, False, []),
        # Test không ổn định: kết quả phụ thuộc có sản phẩm FLAKYMARK trên target hay không (script bật/tắt giữa các lượt).
        ("X1", "X1 Tìm sản phẩm FLAKYMARK", "GET", P, P + "?name=FLAKYMARK", 200, "DEFAULT", None, None, False,
         [{"jsonPath": "$[0].name", "operator": "EXISTS", "expectedValue": None}]),
    ]


def main():
    ap = argparse.ArgumentParser(description="Seed dữ liệu demo (không gọi AI)")
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8081", help="URL shop-api-target tính từ máy chạy script")
    ap.add_argument("--target-url", default="http://host.docker.internal:8081",
                    help="URL shop-api-target tính từ backend (Docker dùng host.docker.internal)")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--target-user", default="admin")
    ap.add_argument("--target-password", default="admin123")
    ap.add_argument("--recreate", action="store_true", help="xoá project demo cũ (nếu có) rồi seed lại")
    args = ap.parse_args()

    api = Api(args.api)
    target = Api(args.target_local)

    print("1/6 Tài khoản demo:", args.email)
    status, _ = api.call("POST", "/auth/register", {"email": args.email, "password": args.password}, expect_ok=False)
    status, login = api.call("POST", "/auth/login", {"email": args.email, "password": args.password})
    api.token = login["data"]["token"]

    status, projects = api.call("GET", "/projects?page=0&size=100")
    existing = [p for p in projects["data"] if p["name"] == PROJECT_NAME]
    if existing:
        if not args.recreate:
            raise SystemExit(f'Project "{PROJECT_NAME}" đã có. Chạy lại với --recreate để xoá và seed lại.')
        for p in existing:
            api.call("DELETE", f"/projects/{p['id']}")
        print("   đã xoá project demo cũ")

    print("2/6 Lấy token + OpenAPI của shop-api-target")
    status, tok = target.call("POST", "/auth/login", {"username": args.target_user, "password": args.target_password})
    good_token = tok["token"]
    target.token = good_token
    req = urllib.request.Request(args.target_local + "/v3/api-docs", headers={"Authorization": "Bearer " + good_token})
    spec_bytes = urllib.request.urlopen(req, timeout=30).read()

    print("3/6 Tạo project, import OpenAPI, đặt xác thực")
    status, created = api.call("POST", "/projects", {"name": PROJECT_NAME, "description": "Dữ liệu demo - tạo bằng scripts/seed-demo.py (không dùng AI)"})
    pid = created["data"]["id"]
    body, hdr = multipart({"targetBaseUrl": args.target_url}, "openapi.json", spec_bytes)
    api.call("POST", f"/projects/{pid}/endpoints/import", raw=body, headers=hdr)
    api.call("PUT", f"/projects/{pid}/target-auth", {"authType": "BEARER_TOKEN", "authValue": good_token})
    status, eps = api.call("GET", f"/projects/{pid}/endpoints?page=0&size=100")
    endpoint_id = {(e["method"], e["path"]): e["id"] for e in eps["data"]}

    print("4/6 Tạo test case thủ công")
    ids = {}
    for key, name, method, spec_path, resolved, expected, auth, body_json, dep, fallback, assertions in case_table():
        payload = {"name": name, "expectedStatus": expected, "resolvedPath": resolved, "authOverride": auth,
                   "assertions": assertions}
        if body_json:
            payload["requestBody"] = body_json
            payload["requestHeaders"] = JSON_HEADERS
        if "{{id}}" in resolved:
            payload["pathParamFallbacks"] = json.dumps({"id": UNKNOWN_ID})
        if dep:
            payload["dependencies"] = [{"dependsOnTestCaseId": ids[dep], "jsonPath": "$.id", "placeholderName": "id"}]
        status, tc = api.call("POST", f"/projects/{pid}/endpoints/{endpoint_id[(method, spec_path)]}/test-cases", payload)
        ids[key] = tc["data"]["id"]
    print(f"   đã tạo {len(ids)} test case")

    # ---- sản phẩm FLAKYMARK trên target: có/không giữa các lượt để test X1 đổi Pass/Fail tất định ----
    def flaky_product_ids():
        status, found = target.call("GET", "/api/products?name=FLAKYMARK")
        return [p["id"] for p in found] if isinstance(found, list) else []

    def set_flaky_present(present):
        current = flaky_product_ids()
        if present and not current:
            target.call("POST", "/api/products", {"name": "FLAKYMARK", "description": "demo", "price": 1, "stock": 1})
        if not present:
            for pid_ in current:
                target.call("DELETE", f"/api/products/{pid_}")

    def run_round(label):
        status, ex = api.call("POST", f"/projects/{pid}/executions", {"testCaseIds": list(ids.values())})
        eid = ex["data"]["id"]
        for _ in range(120):
            status, detail = api.call("GET", f"/projects/{pid}/executions/{eid}")
            if detail["data"]["status"] not in ("PENDING", "RUNNING"):
                break
            time.sleep(1)
        counts = {}
        for r in detail["data"]["results"]:
            cause = (r.get("failureCause") or {}).get("code")
            k = r["status"] + (f"/{cause}" if cause else "")
            counts[k] = counts.get(k, 0) + 1
        print(f"   {label}: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
        return detail["data"]

    print("5/6 Chạy 7 lượt (lượt 4: token sai tạm thời)")
    set_flaky_present(False)
    # X1: Pass, Fail, Pass, (lượt xác thực), Fail, Pass, Fail -> đổi Pass<->Fail 5 lần trong 6 lượt hợp lệ => FLAKY
    plan = {1: True, 2: False, 3: True, 4: True, 5: False, 6: True, 7: False}
    for n in range(1, 8):
        set_flaky_present(plan[n])
        if n == 4:
            api.call("PUT", f"/projects/{pid}/target-auth", {"authType": "BEARER_TOKEN", "authValue": "demo-expired-token"})
        last = run_round(f"lượt {n}" + (" (token sai)" if n == 4 else ""))
        if n == 4:
            api.call("PUT", f"/projects/{pid}/target-auth", {"authType": "BEARER_TOKEN", "authValue": good_token})
    set_flaky_present(False)
    for r in last["results"]:
        if r.get("stability"):
            st = r["stability"]
            print(f'   độ ổn định "{r["testCaseName"]}": {st["state"]} ({st["runs"]} lần chạy, đổi Pass/Fail {st["flips"]} lần)')

    print("6/6 Sinh bug report hàng loạt (tất định, bỏ qua fail do môi trường/token/không ổn định)")
    status, gen = api.call("POST", f"/projects/{pid}/bug-reports/generate-batch", {})
    print(f"   tạo {len(gen['data']['created'])} bug, bỏ qua {gen['data']['skippedCount']} kết quả")

    print()
    print("XONG. Đăng nhập vào giao diện bằng:")
    print(f"   email: {args.email}   mật khẩu: {args.password}")
    print(f'   project: "{PROJECT_NAME}" (id {pid})')


if __name__ == "__main__":
    sys.exit(main())
