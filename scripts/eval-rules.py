"""17q G5 - đo AI TRÍCH LUẬT từ tài liệu nghiệp vụ và cấu hình C (test sinh từ luật đã duyệt) trên mutant-api.

Hai pha để người đo gán nhãn luật ĐỘC LẬP giữa chừng (không để máy tự chấm đúng/sai):

  Pha 1 (TỐN TOKEN, có trần cứng):  python scripts/eval-rules.py extract --runs 3 --token-cap 25000
      Mỗi lượt: project mới + 10 test gốc người viết + tải SRS + job RULES. Ghi các luật đề xuất ra docs/eval/rules-extract-<ngày>.json
      và GIỮ project (để pha 2 dùng lại). In token của job và chênh lệch sổ CSDL trước/sau.

  Pha 2 (0 token):  python scripts/eval-rules.py evaluate --extract docs/eval/rules-extract-<ngày>.json --labels docs/eval/rules-labels-<ngày>.json
      labels: {"<id luật>": {"correct": true|false, "gold": "R28"|null}} do người đo điền sau khi đọc luật + đáp án.
      Với mỗi lượt đo 2 biến thể (cùng 1 lần trích, không tốn thêm token):
        C-ALL    = duyệt MỌI luật có trích dẫn (người duyệt tin trích dẫn, không kiểm ý nghĩa)
        C-VERIFIED = chỉ duyệt luật gán nhãn đúng (người duyệt cẩn thận: cận trên)
      Bằng chứng = test sinh từ luật; test gốc chỉ là bối cảnh. Đo trên bộ phát triển và held-out (--include-heldout).
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
shopm = load("shopm", "eval-shop-mutants.py")  # chỉ để dùng ledger_total

SRS = REPO / "docs" / "eval" / "srs-mutant-api.md"


def rules_of(system, pid):
    rules, page = [], 0
    while True:
        _, r = system.api.call("GET", f"/projects/{pid}/business-rules?page={page}&size=200")
        rules += r["data"]
        if len(r["data"]) < 200 or page > 20:
            return rules
        page += 1


def compact(rule):
    return {"id": rule["id"], "endpoint": rule["endpointKey"], "template": rule["template"], "params": rule["params"], "sentence": rule["sentence"],
            "source": rule["source"], "status": rule["status"], "expectedStatus": rule["expectedStatus"], "quote": rule["quote"],
            "hasQuote": rule["quote"] is not None, "statusNote": rule.get("statusNote")}


def extract(args, log):
    target = evalm.Target(args.target_local)
    system = evalm.System(args.api, args.email, args.password)
    before = shopm.ledger_total()
    log(f"Sổ token TRƯỚC: {before}")
    runs = []
    for n in range(1, args.runs + 1):
        stamp = datetime.datetime.now().strftime("%m%d-%H%M%S")
        target.reset()
        hr, cand = target.login("hr1@demo.local"), target.login("cand1@demo.local")
        pid = system.create_project(f"EVAL-RULES-{n}-{stamp}", "Đo trích luật bằng AI (scripts/eval-rules.py)")
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
        body, hdr = evalm.multipart({}, "srs-mutant-api.md", SRS.read_bytes())
        system.api.call("POST", f"/projects/{pid}/requirement-documents", raw=body, headers=hdr)
        t0 = time.time()
        job = system.generate(pid, {"type": "RULES", "endpointIds": [e["id"] for e in eps], "tokenCap": args.token_cap, "allowInferred": True},
                              timeout=1800, progress=lambda j: log(f"    ... job {j['status']} {j['tokensUsed']} token"))
        seconds = round(time.time() - t0, 1)
        rules = rules_of(system, pid)
        log(f"  lượt {n}: job {job['status']}, {job['tokensUsed']} token, {seconds}s, {len(rules)} luật đề xuất; kết quả {job.get('rules')}")
        runs.append({"run": n, "projectId": pid, "jobId": job["id"], "jobStatus": job["status"], "tokens": job["tokensUsed"], "seconds": seconds,
                     "extraction": job.get("rules"), "steps": [{"phase": s["phase"], "status": s["status"], "detail": s["detail"], "tokens": s["tokens"]}
                                                               for s in job["steps"]], "rules": [compact(r) for r in rules]})
    after = shopm.ledger_total()
    log(f"Sổ token SAU: {after}")
    out = {"environment": evalm.environment_info(args), "tokenAccounting": {
        "before": before, "after": after, "eventTokens": after["eventTokens"] - before["eventTokens"],
        "jobTokens": after["jobTokens"] - before["jobTokens"], "newEventRows": after["eventRows"] - before["eventRows"]}, "runs": runs}
    path = pathlib.Path(args.out) if args.out else REPO / "docs" / "eval" / f"rules-extract-{datetime.date.today()}.json"
    path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"\nTOKEN: chênh lệch sổ sinh test = {out['tokenAccounting']['eventTokens']}, tổng token các job = {sum(r['tokens'] for r in runs)}")
    log(f"Đã ghi {path}. Bước tiếp: gán nhãn từng luật, rồi chạy pha evaluate.")


def evaluate_runs(args, log):
    extract_data = json.loads(pathlib.Path(args.extract).read_text(encoding="utf-8"))
    labels = json.loads(pathlib.Path(args.labels).read_text(encoding="utf-8"))
    target = evalm.Target(args.target_local)
    catalog = target.catalog()
    mutants = catalog if args.include_heldout else [m for m in catalog if not m.get("heldout")]
    system = evalm.System(args.api, args.email, args.password)
    results = []
    for run in extract_data["runs"]:
        pid = run["projectId"]
        rules = {r["id"]: r for r in rules_of(system, pid)}
        cited = [i for i, r in rules.items() if r["quote"] and r["source"] in ("DOC", "INSTRUCTION") and r["status"] == "PROPOSED"]
        verified = [i for i in cited if labels.get(i, {}).get("correct") is True]
        rest = [i for i in cited if i not in verified]
        out = {"run": run["run"], "projectId": pid, "citedRules": len(cited), "verifiedRules": len(verified), "configs": {}}
        # 1) duyệt luật đúng -> sinh test (tập C-VERIFIED); 2) duyệt phần còn lại -> sinh thêm (C-ALL = hợp của hai tập)
        if verified:
            system.api.call("POST", f"/projects/{pid}/business-rules/approve-batch", {"ruleIds": verified})
            system.api.call("POST", f"/projects/{pid}/business-rules/generate-tests", {"ruleIds": verified})
        tests_verified = [t for t in system.tests(pid) if t.get("ruleId") in set(verified)]
        if rest:
            system.api.call("POST", f"/projects/{pid}/business-rules/approve-batch", {"ruleIds": rest})
            system.api.call("POST", f"/projects/{pid}/business-rules/generate-tests", {"ruleIds": rest})
        all_tests = system.tests(pid)
        tests_all = [t for t in all_tests if t.get("ruleId") in set(cited)]
        context = [t for t in all_tests if t.get("source") == "MANUAL" and not t.get("ruleId") and not t.get("probeKind")]
        log(f"  lượt {run['run']}: {len(cited)} luật có trích dẫn, {len(verified)} đúng; test từ luật: ALL {len(tests_all)}, VERIFIED {len(tests_verified)}")
        out["ruleTests"] = {"all": len(tests_all), "verified": len(tests_verified)}
        if tests_verified:
            out["configs"]["C_VERIFIED"] = evalm.evaluate(system, target, pid, tests_verified + context, mutants, "C_VERIFIED", log,
                                                          evidence_ids={t["id"] for t in tests_verified})
        if tests_all:
            out["configs"]["C_ALL"] = evalm.evaluate(system, target, pid, tests_all + context, mutants, "C_ALL", log,
                                                     evidence_ids={t["id"] for t in tests_all})
        results.append(out)
        if not args.keep_projects:
            system.delete_project(pid)
    summary = {}
    for cfg in ("C_VERIFIED", "C_ALL"):
        s = evalm.summarize(results, cfg)
        if s:
            summary[cfg] = s
            evalm.print_summary(cfg, s, results)
    path = pathlib.Path(args.out) if args.out else REPO / "docs" / "eval" / f"results-C-rules-{datetime.date.today()}.json"
    path.write_text(json.dumps({"environment": evalm.environment_info(args), "mutantCatalog": [m for m in catalog if m in mutants],
                                "labelsFile": args.labels, "runs": results, "summary": summary}, ensure_ascii=False, indent=2), encoding="utf-8")
    log(f"Đã ghi {path}")


def main():
    ap = argparse.ArgumentParser(description="Đo trích luật bằng AI và cấu hình C")
    ap.add_argument("phase", choices=["extract", "evaluate"])
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--token-cap", type=int, default=25000, help="trần token cứng MỖI lượt trích")
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8083")
    ap.add_argument("--target-url", default="http://host.docker.internal:8083")
    ap.add_argument("--email", default="eval@aiapi.local")
    ap.add_argument("--password", default="eval12345")
    ap.add_argument("--model", default="claude-sonnet-5")
    ap.add_argument("--extract", default="")
    ap.add_argument("--labels", default="")
    ap.add_argument("--include-heldout", action="store_true")
    ap.add_argument("--keep-projects", action="store_true")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    log = lambda msg: print(msg, flush=True)
    if args.phase == "extract":
        extract(args, log)
    else:
        if not args.extract or not args.labels:
            ap.error("pha evaluate cần --extract và --labels")
        evaluate_runs(args, log)
    return 0


if __name__ == "__main__":
    sys.exit(main())
