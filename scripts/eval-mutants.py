#!/usr/bin/env python3
"""
Đo độ chính xác của AI API Testing Assistant bằng LỖI CÀI SẴN (Module 17o - mutation evaluation).

Ý tưởng: mutant-api là API tuyển dụng thu nhỏ có 27 lỗi cài sẵn (mutant) bật/tắt lúc chạy. Với mỗi cấu hình sinh test:
  1. sinh test trên project tạo mới (import OpenAPI của BẢN CHUẨN),
  2. chạy test trên bản chuẩn -> test Pass là "xác nhận đúng"; test Fail trên bản chuẩn = FAIL GIẢ (false failure),
  3. với từng mutant: bật mutant, chạy lại ĐÚNG các test đã Pass -> mutant BỊ BẮT nếu có test Pass -> Fail do lỗi của API
     (không tính Fail do môi trường/token/dữ liệu test - theo failureCause.notApiFault của hệ thống).

Ba cấu hình (xem docs/DANH_GIA.md):
  A  chỉ checklist tất định: test gốc do người viết (scripts/eval/baselines.json) + job SYSTEM, 0 token.
  B  hybrid: job FULL (AI sinh test gốc + chạy xác nhận + checklist + AI nghiệp vụ). TỐN TOKEN THẬT.
  C  chỉ AI nghiệp vụ: lấy từ CHÍNH lần sinh của B, chỉ chạy test generationOrigin=AI_BUSINESS (xấp xỉ, xem threats to validity).

Yêu cầu: hệ thống đang chạy (docker compose up) và mutant-api đang chạy với MUTANT_CONTROL=true:
    cd mutant-api && MUTANT_CONTROL=true ./mvnw spring-boot:run          (cổng 8083)
Chạy:
    python scripts/eval-mutants.py --config A                 (0 token)
    python scripts/eval-mutants.py --config B --dry-run       (chỉ in dự toán token, không gọi AI)
    python scripts/eval-mutants.py --config B --yes-spend --runs 3 --token-cap 200000
Chỉ dùng thư viện chuẩn của Python 3. Mẫu gọi API lấy từ scripts/seed-demo.py.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
# Fail có thể do chính cách chạy/dữ liệu chứ chưa chắc lỗi API -> tính riêng cột "nghi ngờ", không cộng vào "bắt được".
AMBIGUOUS = {"FLAKY", "EXPECTATION_MISMATCH", "STATE_CONFLICT", "TEST_DATA_SUSPECT", "TOKEN_FORBIDDEN", "UNDETERMINED"}
EST_TOKENS_PER_ENDPOINT = 12000  # đo thật ở 17n: 6.000-12.000 token/endpoint cho AI test gốc; chỉ để DỰ TOÁN trước khi chi


class ApiError(Exception):
    pass


class Api:
    def __init__(self, base, timeout=120):
        self.base = base.rstrip("/")
        self.token = None
        self.timeout = timeout

    def call(self, method, path, body=None, raw=None, headers=None, expect_ok=True):
        h = {} if raw is not None else {"Content-Type": "application/json"}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        if headers:
            h.update(headers)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                text, status = r.read().decode(), r.status
        except urllib.error.HTTPError as e:
            text, status = e.read().decode(), e.code
        except urllib.error.URLError as e:
            raise ApiError(f"Không kết nối được {self.base}{path}: {e.reason}")
        try:
            parsed = json.loads(text) if text else None
        except ValueError:
            parsed = text
        if expect_ok and status >= 400:
            raise ApiError(f"{method} {path} -> {status}: {str(parsed)[:300]}")
        return status, parsed


def multipart(fields, filename, content):
    boundary = uuid.uuid4().hex
    out = b""
    for k, v in fields.items():
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    out += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f'Content-Type: application/json\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
    return out, {"Content-Type": "multipart/form-data; boundary=" + boundary}


# ---------------------------------------------------------------- mutant-api (API đích)
class Target:
    def __init__(self, local):
        self.api = Api(local)

    def catalog(self):
        status, body = self.api.call("GET", "/__mutants", expect_ok=False)
        if status != 200:
            raise SystemExit("mutant-api chưa bật điều khiển. Chạy: cd mutant-api && MUTANT_CONTROL=true ./mvnw spring-boot:run")
        return body["catalog"]

    def reset(self):
        self.api.call("POST", "/__reset")

    def enable(self, ids):
        self.api.call("POST", "/__mutants", {"enabled": list(ids)})

    def login(self, email):
        _, body = self.api.call("POST", "/auth/login", {"email": email, "password": "Passw0rd!"})
        return body["token"]

    def spec(self):
        req = urllib.request.Request(self.api.base + "/v3/api-docs")
        return urllib.request.urlopen(req, timeout=30).read()


# ---------------------------------------------------------------- hệ thống đo
class System:
    def __init__(self, base, email, password):
        self.api = Api(base)
        self.api.call("POST", "/auth/register", {"email": email, "password": password}, expect_ok=False)
        _, login = self.api.call("POST", "/auth/login", {"email": email, "password": password})
        self.api.token = login["data"]["token"]

    def create_project(self, name, description):
        return self.api.call("POST", "/projects", {"name": name, "description": description})[1]["data"]["id"]

    def delete_project(self, pid):
        self.api.call("DELETE", f"/projects/{pid}", expect_ok=False)

    def import_spec(self, pid, spec_bytes, target_url):
        body, hdr = multipart({"targetBaseUrl": target_url}, "openapi.json", spec_bytes)
        self.api.call("POST", f"/projects/{pid}/endpoints/import", raw=body, headers=hdr)

    def set_target_auth(self, pid, token):
        self.api.call("PUT", f"/projects/{pid}/target-auth", {"authType": "BEARER_TOKEN", "authValue": token})

    def create_profile(self, pid, name, token):
        _, r = self.api.call("POST", f"/projects/{pid}/auth-profiles",
                             {"name": name, "authType": "BEARER_TOKEN", "authValue": token})
        return r["data"]["id"]

    def endpoints(self, pid):
        return self.api.call("GET", f"/projects/{pid}/endpoints?page=0&size=100")[1]["data"]

    def create_test(self, pid, eid, payload):
        return self.api.call("POST", f"/projects/{pid}/endpoints/{eid}/test-cases", payload)[1]["data"]["id"]

    def tests(self, pid):
        return self.api.call("GET", f"/projects/{pid}/test-cases")[1]["data"]

    def run(self, pid, test_ids, timeout=900):
        _, ex = self.api.call("POST", f"/projects/{pid}/executions", {"testCaseIds": list(test_ids)})
        eid = ex["data"]["id"]
        deadline = time.time() + timeout
        while time.time() < deadline:
            _, detail = self.api.call("GET", f"/projects/{pid}/executions/{eid}")
            if detail["data"]["status"] not in ("PENDING", "RUNNING"):
                return detail["data"]["results"]
            time.sleep(1)
        raise ApiError(f"Lượt chạy {eid} quá {timeout}s")

    def generate(self, pid, body, timeout, progress=None):
        _, started = self.api.call("POST", f"/projects/{pid}/generation-jobs", body)
        jid = started["data"]["id"]
        deadline = time.time() + timeout
        last = 0
        while time.time() < deadline:
            _, r = self.api.call("GET", f"/projects/{pid}/generation-jobs/{jid}")
            job = r["data"]
            if job["status"] not in ("QUEUED", "RUNNING", "CANCELLING"):
                _, full = self.api.call("GET", f"/projects/{pid}/generation-jobs/{jid}?afterSeq=0")
                return full["data"]
            if progress and time.time() - last > 15:
                last = time.time()
                progress(job)
            time.sleep(2)
        raise ApiError(f"Job sinh test {jid} quá {timeout}s")


# ---------------------------------------------------------------- sinh test
def load_baselines():
    return json.loads((HERE / "eval" / "baselines.json").read_text(encoding="utf-8"))["baselines"]


def load_roles():
    return json.loads((HERE / "eval" / "baselines.json").read_text(encoding="utf-8"))["roles"]


def coverage_fill(system, pid, endpoint_id, hr_profile, cand_profile):
    """Phần 'không AI' còn lại của hệ thống: ma trận phân quyền -> phạm vi OWASP -> nút 'Bù coverage' từng endpoint.
    Sinh thêm test kiểm quyền (vai bị cấm phải bị từ chối) và test dò mass-assignment (17b)."""
    profile = {"HR": hr_profile, "CAND": cand_profile}
    rules = []
    for r in load_roles():
        eid = endpoint_id[(r["method"], r["specPath"])]
        for role in r["allow"]:
            rules.append({"endpointIds": [eid], "profileId": profile[role], "expectation": "ALLOW"})
        for role in r["deny"]:
            rules.append({"endpointIds": [eid], "profileId": profile[role], "expectation": "DENY"})
    system.api.call("PUT", f"/projects/{pid}/role-matrix", {"rules": rules})
    added = 0
    for eid in endpoint_id.values():
        system.api.call("PUT", f"/projects/{pid}/endpoints/{eid}/coverage/scope",
                        {"positive": True, "negative": True, "boundary": True, "security": True, "owasp": True}, expect_ok=False)
        status, r = system.api.call("POST", f"/projects/{pid}/endpoints/{eid}/coverage/fill", {}, expect_ok=False)
        if status < 300 and isinstance(r, dict):
            added += len((r.get("data") or {}).get("created") or []) if isinstance((r.get("data") or {}).get("created"), list) else 0
    return len(rules), added


def create_baselines(system, pid, endpoint_id, cand_profile):
    ids = {}
    for b in load_baselines():
        payload = {"name": b["name"], "expectedStatus": b["expected"], "resolvedPath": b["resolvedPath"],
                   "authOverride": "NONE" if b["profile"] == "NONE" else "DEFAULT", "assertions": []}
        if b.get("body") is not None:
            payload["requestBody"] = json.dumps(b["body"])
            payload["requestHeaders"] = '{"Content-Type":"application/json"}'
        if "{{" in b["resolvedPath"].replace("{{today", ""):
            payload["pathParamFallbacks"] = json.dumps({b["dep"]["placeholder"]: "987654"} if b.get("dep") else {})
        if b["profile"] == "CAND":
            payload["authProfileId"] = cand_profile
        if b.get("dep"):
            payload["dependencies"] = [{"dependsOnTestCaseId": ids[b["dep"]["from"]], "jsonPath": b["dep"]["jsonPath"],
                                        "placeholderName": b["dep"]["placeholder"]}]
        ids[b["key"]] = system.create_test(pid, endpoint_id[(b["method"], b["specPath"])], payload)
    return ids


def candidate_endpoints():
    """(method, path) của các endpoint mà người viết test gốc dùng profile ứng viên (CAND)."""
    return {(b["method"], b["specPath"]) for b in load_baselines() if b["profile"] == "CAND"}


def assign_profiles(system, pid, tests, cand_profile):
    """Mô phỏng bước 'gán AuthProfile thủ công' mà roadmap vẫn yêu cầu người dùng làm.

    Test checklist do hệ thống bù KHÔNG thừa kế authProfileId của test gốc (CoverageGapFillService.toEntity; chỉ bản
    mass-assignment có copy) nên mọi test dẫn xuất của endpoint cần vai khác sẽ chạy bằng token mặc định và ra 403 - Fail giả
    do công cụ, che mất việc đo phát hiện lỗi. Tắt bằng --no-assign-profiles để đo đúng hiện trạng (xem docs/DANH_GIA.md)."""
    targets = candidate_endpoints()
    assigned = failed = 0
    for t in tests:
        if (t["endpointMethod"], t["endpointPath"]) not in targets or t.get("authProfileId"):
            continue
        payload = {"name": t["name"], "description": t.get("description"), "requestHeaders": t.get("requestHeaders"),
                   "requestBody": t.get("requestBody"), "expectedStatus": t["expectedStatus"],
                   "resolvedPath": t.get("resolvedPath"), "pathParamFallbacks": t.get("pathParamFallbacks"),
                   "authOverride": t.get("authOverride") or "DEFAULT", "authProfileId": cand_profile,
                   "dependencies": [{"dependsOnTestCaseId": d["dependsOnTestCaseId"], "jsonPath": d["jsonPath"],
                                     "placeholderName": d["placeholderName"]} for d in t.get("dependencies") or []],
                   "assertions": [{"jsonPath": a["jsonPath"], "operator": a["operator"], "expectedValue": a.get("expectedValue")}
                                  for a in t.get("assertions") or []]}
        # test mà người dùng muốn chạy bằng token sai/không token thì KHÔNG đổi sang token ứng viên
        if payload["authOverride"] != "DEFAULT":
            continue
        status, _ = system.api.call("PUT", f"/projects/{pid}/endpoints/{t['endpointId']}/test-cases/{t['id']}", payload,
                                    expect_ok=False)
        if status < 300:
            assigned += 1
        else:
            failed += 1
    return assigned, failed


def classify(result):
    """Kết quả 1 test (đã Pass trên bản chuẩn) khi chạy trên bản mutant -> 'ok' | 'caught' | 'suspect' | 'env'.

    Thí nghiệm có kiểm soát: chỉ lật ĐÚNG 1 mutant nên Pass -> Fail chắc chắn do mutant. Vì thế đo 2 chỉ số tách bạch:
      - 'phát hiện' = caught + suspect (test Pass -> FAILED không do môi trường/token/dữ liệu test),
      - 'gán đúng lỗi API' = chỉ caught: nhãn nguyên nhân của hệ thống KHÔNG nằm trong tập mơ hồ (AMBIGUOUS). suspect = hệ
        thống thấy Fail nhưng chưa dám kết luận là lỗi API (người dùng sẽ phải tự xét).
    ERROR/BLOCKED/SKIPPED và notApiFault=true (môi trường, token, tài nguyên không tồn tại) KHÔNG tính là phát hiện."""
    if result["status"] == "PASSED":
        return "ok"
    cause = result.get("failureCause") or {}
    code = cause.get("code")
    if result["status"] != "FAILED":
        return "env"                      # ERROR/BLOCKED/SKIPPED: lỗi hạ tầng/phụ thuộc, không phải API sai
    if cause.get("notApiFault"):
        return "env"
    if code is None or code in AMBIGUOUS:
        return "suspect"
    return "caught"


def evaluate(system, target, pid, tests, mutants, label, log, evidence_ids=None):
    """Chạy `tests` trên bản chuẩn rồi trên từng mutant. Trả về dict kết quả.

    evidence_ids (cấu hình C): tập test được TÍNH bằng chứng/Fail giả. `tests` còn chứa thêm test gốc làm BỐI CẢNH (tạo tin/đơn
    mà test nghiệp vụ cần) nhưng test bối cảnh không được tính là phát hiện - nếu không C sẽ được ghi công của checklist."""
    names = {t["id"]: t["name"] for t in tests}
    ids = [t["id"] for t in tests]
    scope = set(evidence_ids) if evidence_ids is not None else set(ids)
    log(f"  [{label}] chạy {len(ids)} test trên bản chuẩn")
    target.reset()
    clean = system.run(pid, ids)
    clean_pass = [r["testCaseId"] for r in clean if r["status"] == "PASSED"]
    clean_findings = {r["testCaseId"] for r in clean if r.get("securityFindings")}
    false_causes = {}
    false_details = []
    for r in clean:
        if r["status"] != "PASSED" and r["testCaseId"] in scope:
            code = (r.get("failureCause") or {}).get("code") or r["status"]
            false_causes[code] = false_causes.get(code, 0) + 1
            false_details.append({"test": names.get(r["testCaseId"], r["testCaseId"]), "cause": code,
                                  "expected": r.get("expectedStatus"), "actual": r.get("responseStatus")})
    scope_pass = len([i for i in clean_pass if i in scope])
    log(f"  [{label}] bản chuẩn: {scope_pass}/{len(scope)} Pass (tính bằng chứng), Fail giả {len(scope) - scope_pass} {false_causes}")

    # LƯỢT ĐỐI CHỨNG: chạy lại ĐÚNG tập test sẽ dùng cho mutant nhưng KHÔNG bật mutant nào (2 lần). Test Pass ở bản chuẩn đầy đủ
    # mà vẫn Fail ở đây là NHIỄU của cách đo (vd phụ thuộc trạng thái do test khác tạo, mà tập con không còn test đó) -> loại khỏi bằng
    # chứng. Phát hiện khi bản đo đầu tiên cho mọi mutant ra cùng 1 con số "3 bắt, 2 nghi ngờ" dù mutant khác nhau.
    noise = set()
    for _ in range(2):
        target.reset()
        for r in system.run(pid, clean_pass):
            if r["status"] != "PASSED":
                noise.add(r["testCaseId"])
    log(f"  [{label}] lượt đối chứng (không mutant): {len(noise)} test nhiễu bị loại khỏi bằng chứng")

    per_mutant = []
    for m in mutants:
        target.reset()
        target.enable([m["id"]])
        results = system.run(pid, clean_pass)
        caught, suspect, findings = [], [], []
        for r in results:
            if r["testCaseId"] in noise or r["testCaseId"] not in scope:
                continue
            if r.get("securityFindings") and r["testCaseId"] not in clean_findings:
                findings.append({"test": names.get(r["testCaseId"], r["testCaseId"]), "status": r["status"],
                                 "findings": json.dumps(r["securityFindings"], ensure_ascii=False)[:300]})
            c = classify(r)
            code = (r.get("failureCause") or {}).get("code")
            evidence = {"test": names.get(r["testCaseId"], r["testCaseId"]), "cause": code,
                        "expected": r.get("expectedStatus"), "actual": r.get("responseStatus")}
            if c == "caught":
                caught.append(evidence)
            elif c == "suspect":
                suspect.append(evidence)
        outcome = "caught" if caught else "suspect" if suspect else "flagged" if findings else "missed"
        per_mutant.append({"id": m["id"], "category": m["category"], "expectChecklist": m["expectChecklist"],
                           "outcome": outcome, "caughtBy": caught[:5], "caughtCount": len(caught),
                           "suspectBy": suspect[:5], "suspectCount": len(suspect),
                           "securityFindings": findings[:5], "securityFindingsCount": len(findings)})
        log(f"    {m['id']} {m['category']:<16} -> {outcome:<7} ({len(caught)} bắt, {len(suspect)} nghi ngờ)")
    target.reset()
    return {"label": label, "tests": len(scope), "contextTests": len(ids) - len(scope), "cleanPass": scope_pass,
            "falseFailures": len(scope) - scope_pass,
            "falseFailureCauses": false_causes, "falseFailureDetails": false_details,
            "noiseTests": [names.get(i, i) for i in noise], "mutants": per_mutant}


def reuse_project(args, config, run_no, target, system, mutants, log):
    """Đánh giá lại project ĐÃ sinh test (không gọi AI, không tốn token) - dùng khi chỉ sửa cách đo."""
    pid = args.reuse_project
    tests = system.tests(pid)
    origins = {}
    for t in tests:
        k = (t.get("generationOrigin") or t.get("source")) + (f"/{t['probeKind']}" if t.get("probeKind") else "")
        origins[k] = origins.get(k, 0) + 1
    _, jobs = system.api.call("GET", f"/projects/{pid}/generation-jobs")
    job = (jobs["data"] or [{}])[0]
    gen = {"reusedProject": pid, "jobStatus": job.get("status"), "tokens": job.get("tokensUsed", 0),
           "seconds": args.reuse_seconds, "phaseTokens": {}, "note": "đánh giá lại project đã sinh, không sinh lại"}
    log(f"  dùng lại project {pid}: {len(tests)} test, job sinh test cũ tốn {gen['tokens']} token")
    out = {"run": run_no, "projectId": pid, "generation": gen, "testsByOrigin": origins, "configs": {}}
    out["configs"][config] = evaluate(system, target, pid, tests, mutants, config, log)
    if config == "B":
        business = [t for t in tests if t.get("generationOrigin") == "AI_BUSINESS"]
        gen["businessTests"] = len(business)
        if business:
            context = [t for t in tests if t.get("generationOrigin") in ("AI_BASELINE",) or t.get("source") == "MANUAL"]
            out["configs"]["C"] = evaluate(system, target, pid, business + context, mutants, "C", log,
                                           evidence_ids={t["id"] for t in business})
    return out


def run_once(args, config, run_no, target, system, mutants, log):
    if args.reuse_project:
        return reuse_project(args, config, run_no, target, system, mutants, log)
    stamp = datetime.datetime.now().strftime("%m%d-%H%M%S")
    target.reset()
    hr, cand = target.login("hr1@demo.local"), target.login("cand1@demo.local")
    pid = system.create_project(f"EVAL-{config}-{run_no}-{stamp}", "Đo độ chính xác bằng mutant (scripts/eval-mutants.py)")
    system.import_spec(pid, target.spec(), args.target_url)
    system.set_target_auth(pid, hr)
    cand_profile = system.create_profile(pid, "CANDIDATE", cand)
    hr_profile = system.create_profile(pid, "HR", hr)
    eps = system.endpoints(pid)
    endpoint_id = {(e["method"], e["path"]): e["id"] for e in eps}
    log(f"  project {pid}: {len(eps)} endpoint đã import")

    if config == "A" or args.seed_baselines:
        ids = create_baselines(system, pid, endpoint_id, cand_profile)
        base_results = system.run(pid, list(ids.values()))
        bad = [r["testCaseName"] for r in base_results if r["status"] != "PASSED"]
        if bad:
            raise SystemExit(f"Test gốc không Pass trên bản chuẩn (lỗi fixture): {bad}")
        log(f"  {len(ids)} test gốc do người viết: tất cả Pass")

    endpoint_ids = [e["id"] for e in eps if e["path"] not in args.skip_paths]
    body = {"endpointIds": endpoint_ids, "confirmWrite": True, "includePositive": True, "includeNegative": True,
            "includeBoundary": True, "includeSecurity": True}
    if config == "A":
        body["type"] = "SYSTEM"
    else:
        body.update({"type": "FULL", "includeBusiness": True, "tokenCap": args.token_cap})
    t0 = time.time()
    job = system.generate(pid, body, timeout=3600 if config != "A" else 900,
                          progress=lambda j: log(f"    ... job {j['status']} {j['tokensUsed']} token"))
    seconds = round(time.time() - t0, 1)
    phase_tokens = {}
    for s in job.get("steps", []):
        phase_tokens[s["phase"]] = phase_tokens.get(s["phase"], 0) + (s.get("tokens") or 0)
    gen = {"jobStatus": job["status"], "tokens": job["tokensUsed"], "seconds": seconds, "phaseTokens": phase_tokens,
           "endpointResults": [{"label": r["label"], "status": r["status"], "reason": r.get("reason"), "tokens": r["tokens"]}
                               for r in job.get("results", [])]}
    log(f"  sinh test xong: {job['status']}, {job['tokensUsed']} token, {seconds}s")

    tests = system.tests(pid)
    fill_note = None
    if not args.no_coverage_fill:  # cả A và B dùng cùng phần không-AI để chênh lệch A↔B chỉ là đóng góp của AI
        n_rules, added = coverage_fill(system, pid, endpoint_id, hr_profile, cand_profile)
        fill_note = {"roleRules": n_rules, "reportedCreated": added, "testsBefore": len(tests)}
        tests = system.tests(pid)
        fill_note["testsAfter"] = len(tests)
        log(f"  ma trận quyền {n_rules} luật + Bù coverage: {fill_note['testsBefore']} -> {fill_note['testsAfter']} test")
    gen["coverageFill"] = fill_note
    profile_note = None
    if not args.no_assign_profiles:
        assigned, failed = assign_profiles(system, pid, tests, cand_profile)
        profile_note = {"assigned": assigned, "failed": failed}
        log(f"  gán profile ứng viên cho {assigned} test dẫn xuất ({failed} lỗi)")
        tests = system.tests(pid)
    gen["profileAssignment"] = profile_note
    origins = {}
    for t in tests:
        k = (t.get("generationOrigin") or t.get("source")) + (f"/{t['probeKind']}" if t.get("probeKind") else "")
        origins[k] = origins.get(k, 0) + 1
    out = {"run": run_no, "projectId": pid, "generation": gen, "testsByOrigin": origins, "configs": {}}
    out["configs"][config] = evaluate(system, target, pid, tests, mutants, config, log)
    if config == "B":
        business = [t for t in tests if t.get("generationOrigin") == "AI_BUSINESS"]
        gen["businessTests"] = len(business)
        if business:
            context = [t for t in tests if t.get("generationOrigin") in ("AI_BASELINE",) or t.get("source") == "MANUAL"]
            out["configs"]["C"] = evaluate(system, target, pid, business + context, mutants, "C", log,
                                           evidence_ids={t["id"] for t in business})
    if not args.keep_projects:
        system.delete_project(pid)
    return out


# ---------------------------------------------------------------- tổng hợp
def summarize(runs, config):
    """Tỉ lệ bắt theo loại + tổng; với nhiều lượt: trung bình ± độ lệch chuẩn."""
    per_run = [r["configs"][config] for r in runs if config in r["configs"]]
    if not per_run:
        return None

    def rate(selected, flag):
        n = len(selected)
        return sum(1 for m in selected if m["outcome"] == "caught" or (flag and m["outcome"] in ("suspect",))) / n if n else 0.0

    categories = sorted({m["category"] for m in per_run[0]["mutants"]})
    table = {}
    for scope in ["ALL"] + categories:
        strict, lenient, counts = [], [], []
        for pr in per_run:
            sel = [m for m in pr["mutants"] if scope == "ALL" or m["category"] == scope]
            strict.append(rate(sel, False))
            lenient.append(rate(sel, True))
            counts.append((sum(1 for m in sel if m["outcome"] == "caught"), len(sel)))
        flagged = [sum(1 for m in pr["mutants"] if (scope == "ALL" or m["category"] == scope) and m["outcome"] == "flagged")
                   for pr in per_run]
        table[scope] = {"caughtStrictMean": statistics.mean(strict), "caughtStrictStdev": statistics.pstdev(strict),
                        "caughtOrSuspectMean": statistics.mean(lenient), "perRun": counts, "flaggedOnlyPerRun": flagged}
    ff = [pr["falseFailures"] / pr["tests"] for pr in per_run if pr["tests"]]
    return {"runs": len(per_run), "detection": table,
            "falseFailureRate": {"mean": statistics.mean(ff) if ff else None, "stdev": statistics.pstdev(ff) if ff else None},
            "avgTests": statistics.mean(pr["tests"] for pr in per_run)}


def print_summary(config, s, runs):
    print(f"\n=== Cấu hình {config} ({s['runs']} lượt, TB {s['avgTests']:.0f} test/lượt) ===")
    print(f"{'Loại':<18}{'gán đúng lỗi API':>18}{'phát hiện':>12}   số lượt (gán đúng/tổng)")
    for scope, v in s["detection"].items():
        sd = f" ± {v['caughtStrictStdev'] * 100:.0f}" if s["runs"] > 1 else ""
        per = " ".join(f"{c}/{t}" for c, t in v["perRun"])
        extra = f"  (+{sum(v['flaggedOnlyPerRun']) / s['runs']:.0f} chỉ có cảnh báo bảo mật)" if any(v["flaggedOnlyPerRun"]) else ""
        print(f"{scope:<18}{v['caughtStrictMean'] * 100:>13.0f}%{sd:<5}{v['caughtOrSuspectMean'] * 100:>10.0f}%        {per}{extra}")
    ff = s["falseFailureRate"]
    if ff["mean"] is not None:
        print(f"Fail giả trên bản chuẩn: {ff['mean'] * 100:.1f}%")
    tokens = [r["generation"]["tokens"] for r in runs]
    secs = [r["generation"]["seconds"] for r in runs]
    print(f"Sinh test: {statistics.mean(tokens):.0f} token, {statistics.mean(secs):.0f}s (trung bình mỗi lượt)")


def environment_info(args):
    def git(*a):
        try:
            return subprocess.run(["git", *a], cwd=REPO, capture_output=True, text=True, timeout=20).stdout.strip()
        except Exception:
            return None
    h = hashlib.sha256()
    prompts = sorted((REPO / "backend/src/main/resources/prompts").rglob("*.st"))
    for p in prompts:
        h.update(p.read_bytes())
    return {"date": datetime.datetime.now().isoformat(timespec="seconds"), "gitCommit": git("rev-parse", "HEAD"),
            "gitDirty": bool(git("status", "--porcelain")), "model": args.model, "promptFiles": len(prompts),
            "promptHash": h.hexdigest()[:16], "args": {k: v for k, v in vars(args).items() if k not in ("password",)}}


def seed_project(args, target, system, log):
    """Chuẩn bị SẴN 1 project bạn đã tạo+import trên giao diện để TEST TAY: đặt token HR, tạo 2 profile (HR, ứng viên) và
    10 test gốc do người viết (đã chạy xác nhận Pass). Không gọi AI, không tốn token. Sau đó bạn tự bấm 'Sinh test case'
    (cách 0 token), chạy test, bật mutant bằng curl và xem hệ thống bắt lỗi."""
    pid = args.seed_project
    target.reset()
    hr, cand = target.login("hr1@demo.local"), target.login("cand1@demo.local")
    system.set_target_auth(pid, hr)
    hr_profile = system.create_profile(pid, "HR", hr)
    cand_profile = system.create_profile(pid, "CANDIDATE", cand)
    eps = system.endpoints(pid)
    endpoint_id = {(e["method"], e["path"]): e["id"] for e in eps}
    if len(eps) != 10:
        log(f"  CẢNH BÁO: project có {len(eps)} endpoint (mong đợi 10) - đã import đúng file mutant-api/openapi.json chưa?")
    ids = create_baselines(system, pid, endpoint_id, cand_profile)
    results = system.run(pid, list(ids.values()))
    bad = [r["testCaseName"] for r in results if r["status"] != "PASSED"]
    target.reset()
    log(f"  đã tạo {len(ids)} test gốc, chạy xác nhận: {len(ids) - len(bad)}/{len(ids)} Pass" + (f"; KHÔNG Pass: {bad}" if bad else ""))
    log(f"  đã đặt token HR làm xác thực mặc định; tạo profile HR ({hr_profile[:8]}...) và CANDIDATE ({cand_profile[:8]}...)")
    log("Xong. Tải lại trang project trên giao diện (Ctrl+F5): tab Test case sẽ có 10 test gốc.")


def merge_results(files, out_path):
    """Gộp nhiều file kết quả (mỗi file là 1+ lượt độc lập) thành 1 bảng: trung bình ± độ lệch chuẩn qua các lượt."""
    runs, env, catalog = [], None, None
    for f in files:
        d = json.loads(pathlib.Path(f).read_text(encoding="utf-8"))
        env = env or d["environment"]
        catalog = catalog or d["mutantCatalog"]
        for r in d["runs"]:
            r = dict(r)
            r["run"] = len(runs) + 1
            r["sourceFile"] = str(f)
            runs.append(r)
    result = {"environment": env, "mutantCatalog": catalog, "runs": runs, "summary": {}, "mergedFrom": [str(f) for f in files]}
    for cfg in ("A", "B", "C"):
        s = summarize(runs, cfg)
        if s:
            result["summary"][cfg] = s
            print_summary(cfg, s, [r for r in runs if cfg in r["configs"]])
    pathlib.Path(out_path).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Đã gộp {len(runs)} lượt -> {out_path}")


def main():
    # Console Windows mặc định cp1252 không in được tiếng Việt.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="Đo độ chính xác bằng lỗi cài sẵn (mutant)")
    ap.add_argument("--seed-project", default="", help="id project ĐÃ tạo+import trên giao diện: đặt token, tạo profile + 10 test gốc để test tay (0 token)")
    ap.add_argument("--merge", nargs="+", default=None, help="gộp các file kết quả (mỗi file 1+ lượt độc lập) -> --out; không chạy gì")
    ap.add_argument("--config", choices=["A", "B"], required=False, help="A = checklist (0 token); B = hybrid AI (+ C lấy từ B)")
    ap.add_argument("--runs", type=int, default=1, help="số lượt lặp (nên 3 cho cấu hình có AI)")
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8083", help="mutant-api tính từ máy chạy script")
    ap.add_argument("--target-url", default="http://host.docker.internal:8083", help="mutant-api tính từ backend (Docker)")
    ap.add_argument("--email", default="eval@aiapi.local")
    ap.add_argument("--password", default="eval12345")
    ap.add_argument("--mutants", default="", help="chỉ đo các mutant này, vd M01,M07 (mặc định: tất cả, trừ bộ held-out)")
    ap.add_argument("--include-heldout", action="store_true",
                    help="17q - đo cả bộ held-out H01-H15 (CHỈ dùng 1 lần ở G5; mặc định loại để không vô tình 'nhìn đề')")
    ap.add_argument("--skip-paths", nargs="*", default=[], help="bỏ qua endpoint theo path (vd để thử 1 endpoint)")
    ap.add_argument("--seed-baselines", action="store_true", help="B: tạo trước test gốc do người viết (bỏ AI sinh test gốc)")
    ap.add_argument("--token-cap", type=int, default=200000, help="trần token cho 1 job AI")
    ap.add_argument("--model", default="claude-sonnet-5", help="ghi vào kết quả (backend: spring.ai.anthropic.chat.options.model)")
    ap.add_argument("--dry-run", action="store_true", help="chỉ in kế hoạch + dự toán, không tạo gì, không tốn token")
    ap.add_argument("--yes-spend", action="store_true", help="BẮT BUỘC để chạy cấu hình B (tốn token thật)")
    ap.add_argument("--no-assign-profiles", action="store_true",
                    help="KHÔNG mô phỏng bước gán profile thủ công (đo đúng hiện trạng: test dẫn xuất mất profile -> 403)")
    ap.add_argument("--no-coverage-fill", action="store_true",
                    help="A: bỏ ma trận phân quyền + OWASP + nút Bù coverage (chỉ job SYSTEM thuần)")
    ap.add_argument("--reuse-project", default="", help="id project ĐÃ sinh test: chỉ đánh giá lại, không sinh lại (không tốn token)")
    ap.add_argument("--reuse-seconds", type=float, default=0.0, help="thời gian sinh test cũ (giây) để ghi vào kết quả khi dùng lại")
    ap.add_argument("--keep-projects", action="store_true", help="giữ project đo để xem trên giao diện")
    ap.add_argument("--out", default="", help="file kết quả JSON (mặc định docs/eval/results-<cấu hình>-<ngày>.json)")
    args = ap.parse_args()
    if args.merge:
        merge_results(args.merge, args.out or str(REPO / "docs" / "eval" / "results-merged.json"))
        return 0
    log = lambda msg: print(msg, flush=True)
    if args.seed_project:
        target = Target(args.target_local)
        target.catalog()
        seed_project(args, target, System(args.api, args.email, args.password), log)
        return 0
    if not args.config:
        ap.error("cần --config A|B (hoặc --merge / --seed-project)")

    target = Target(args.target_local)
    catalog = target.catalog()
    wanted = {x.strip() for x in args.mutants.split(",") if x.strip()}
    mutants = [m for m in catalog if not wanted or m["id"] in wanted]
    if not args.include_heldout:
        mutants = [m for m in mutants if not m.get("heldout")]
    if not mutants:
        raise SystemExit("Không có mutant nào khớp --mutants")
    spec_paths = json.loads(target.spec())["paths"]
    endpoints_total = len(spec_paths)
    # Git Bash (Windows) tự đổi tham số bắt đầu bằng "/" thành đường dẫn Windows -> --skip-paths không khớp gì và job chạy
    # NHẦM CẢ spec (từng đốt 64.593 token thay vì ~12.000). Từ chối ngay nếu có path lạ; dùng MSYS_NO_PATHCONV=1 trên Git Bash.
    unknown = [x for x in args.skip_paths if x not in spec_paths]
    if unknown:
        raise SystemExit(f"--skip-paths không khớp path nào trong spec: {unknown}\n"
                         f"Path hợp lệ: {sorted(spec_paths)}\n"
                         "(Git Bash: đặt MSYS_NO_PATHCONV=1 trước lệnh để không bị đổi '/...' thành đường dẫn Windows)")
    run_ops = [f"{m.upper()} {path}" for path, ops in spec_paths.items() if path not in args.skip_paths
               for m in ops if m in ("get", "post", "put", "patch", "delete")]

    if args.config == "B":
        n_ep = len(run_ops)
        print(f"Sẽ sinh test cho {n_ep} endpoint: {', '.join(run_ops)}")
        est = n_ep * EST_TOKENS_PER_ENDPOINT * args.runs
        print(f"Dự toán token AI (cấu hình B): ~{n_ep} endpoint × {EST_TOKENS_PER_ENDPOINT} token × {args.runs} lượt ≈ {est:,} token "
              f"(trần mỗi job {args.token_cap:,}). Con số chỉ để ước lượng, đo thật mới là kết quả.")
        if not args.dry_run and not args.yes_spend:
            raise SystemExit("Dừng: thêm --yes-spend để chạy thật cấu hình B (tốn token thật).")
    if args.dry_run:
        print(f"[dry-run] cấu hình {args.config}, {args.runs} lượt, {len(mutants)} mutant, mutant-api có {endpoints_total} đường dẫn. Không tạo gì.")
        return 0

    system = System(args.api, args.email, args.password)
    runs = []
    for n in range(1, args.runs + 1):
        log(f"\n--- Lượt {n}/{args.runs} - cấu hình {args.config} ---")
        runs.append(run_once(args, args.config, n, target, system, mutants, log))

    result = {"environment": environment_info(args), "mutantCatalog": catalog, "runs": runs, "summary": {}}
    for cfg in ("A", "B", "C"):
        s = summarize(runs, cfg)
        if s:
            result["summary"][cfg] = s
            print_summary(cfg, s, runs)
    out = pathlib.Path(args.out) if args.out else REPO / "docs" / "eval" / f"results-{args.config}-{datetime.date.today()}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nĐã ghi kết quả: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
