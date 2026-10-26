"""Oracle của mutant-shop: với MỖI mutant, 1 phép kiểm trực tiếp phải (a) đúng hành vi ở bản chuẩn và (b) KHÁC khi mutant bật.
Nếu oracle sai thì mutant không có ý nghĩa đo (giống oracle suite 27/27 của mutant-api). Chạy: python oracle.py [http://localhost:8084]."""
import json
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8084").rstrip("/")


def call(method, path, body=None, token=None, raw=None, ctype="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    headers = {"Content-Type": ctype}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            text, status = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        text, status = e.read().decode(), e.code
    try:
        return status, json.loads(text) if text else None
    except ValueError:
        return status, text


def reset(enabled=()):
    call("POST", "/__reset")
    call("POST", "/__mutants", {"enabled": list(enabled)})


def login(password="admin123"):
    return call("POST", "/auth/login", {"username": "admin", "password": password})


def token():
    return login()[1]["token"]


def product(t, **over):
    body = {"name": "Ao thun", "description": "d", "price": 10, "stock": 5}
    body.update(over)
    return call("POST", "/api/products", body, t)


# mỗi oracle trả về (quan sát) - so sánh giữa bản chuẩn và bản có mutant
def o_s01(t):
    return product(t, price=-1)[0]


def o_s02(t):
    return product(t, name="")[0]


def o_s03(t):
    return product(t, stock=-1)[0]


def o_s04(t):
    pid = product(t)[1]["id"]
    return call("PUT", f"/api/products/{pid}", {"name": "x", "price": -5, "stock": 1}, t)[0]


def o_s05(t):
    return call("GET", "/api/products/999999", token=t)[0]


def o_s06(t):
    return call("DELETE", "/api/products/999999", token=t)[0]


def o_s07(t):
    pid = product(t)[1]["id"]
    call("DELETE", f"/api/products/{pid}", token=t)
    return call("GET", f"/api/products/{pid}", token=t)[0]


def o_s08(t):
    return product(t)[0]


def o_s09(t):
    pid = product(t, stock=1)[1]["id"]
    return call("PATCH", f"/api/products/{pid}/stock?quantity=5&operation=decrease", token=t)[1]["stock"]


def o_s10(t):
    product(t, price=1)
    product(t, price=100)
    return len(call("GET", "/api/products?minPrice=50", token=t)[1])


def o_s11(t):
    product(t, name="Laptop")
    return len(call("GET", "/api/products?name=lap", token=t)[1])


def o_s12(t):
    pid = product(t)[1]["id"]
    return call("GET", f"/api/products/{pid}")[0]


def o_s13(t):
    return login("sai-mat-khau")[0]


def o_s14(t):
    return call("PUT", "/api/products/999999", {"name": "x", "price": 1, "stock": 1}, t)[0]


def o_s15(t):
    return call("POST", "/api/products", raw=b'{"name":', token=t)[0]


ORACLES = {"S01": (o_s01, 400), "S02": (o_s02, 400), "S03": (o_s03, 400), "S04": (o_s04, 400), "S05": (o_s05, 404), "S06": (o_s06, 404),
           "S07": (o_s07, 404), "S08": (o_s08, 201), "S09": (o_s09, 0), "S10": (o_s10, 1), "S11": (o_s11, 1), "S12": (o_s12, 403),
           "S13": (o_s13, 401), "S14": (o_s14, 404), "S15": (o_s15, 400)}

failures = 0
for mid, (oracle, expected_clean) in ORACLES.items():
    reset()
    clean = oracle(token())
    reset([mid])
    mutated = oracle(token())
    reset()
    ok = clean == expected_clean and mutated != clean
    failures += 0 if ok else 1
    print(("OK  " if ok else "FAIL"), mid, f"chuẩn={clean} (kỳ vọng {expected_clean}) mutant={mutated}")
print(f"\n{len(ORACLES) - failures}/{len(ORACLES)} mutant có oracle đúng")
sys.exit(1 if failures else 0)
