"""Dựng project API tuyển dụng có 10 test gốc đã Pass (0 token), KHÔNG tải tài liệu và KHÔNG có luật, để thử luồng
"Sinh test case -> AI bổ sung checklist nghiệp vụ -> duyệt -> sinh test từ checklist" trên giao diện.
Chạy:  python scripts/seed-checklist-flow.py [--name TEN]   (cần mutant-api cổng 8083 với MUTANT_CONTROL=true)
In ra id project."""
import argparse
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("evalm", HERE / "eval-mutants.py")
evalm = importlib.util.module_from_spec(spec)
sys.modules["evalm"] = evalm
spec.loader.exec_module(evalm)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default="http://localhost:8080/api/v1")
    ap.add_argument("--target-local", default="http://localhost:8083")
    ap.add_argument("--target-url", default="http://host.docker.internal:8083")
    ap.add_argument("--email", default="demo@aiapi.local")
    ap.add_argument("--password", default="demo12345")
    ap.add_argument("--name", default="FLOW - Checklist nghiệp vụ")
    args = ap.parse_args()
    target = evalm.Target(args.target_local)
    target.catalog()
    system = evalm.System(args.api, args.email, args.password)
    target.reset()
    hr, cand = target.login("hr1@demo.local"), target.login("cand1@demo.local")
    pid = system.create_project(args.name, "Thử luồng checklist nghiệp vụ (scripts/seed-checklist-flow.py)")
    system.import_spec(pid, target.spec(), args.target_url)
    system.set_target_auth(pid, hr)
    cand_profile = system.create_profile(pid, "CANDIDATE", cand)
    system.create_profile(pid, "HR", hr)
    eps = system.endpoints(pid)
    endpoint_id = {(e["method"], e["path"]): e["id"] for e in eps}
    ids = evalm.create_baselines(system, pid, endpoint_id, cand_profile)
    bad = [r["testCaseName"] for r in system.run(pid, list(ids.values())) if r["status"] != "PASSED"]
    if bad:
        raise SystemExit(f"Test gốc không Pass: {bad}")
    target.reset()
    print(pid)


if __name__ == "__main__":
    sys.exit(main())
