#!/usr/bin/env python3
"""
So sánh với công cụ có sẵn Schemathesis (E1c): cùng mutant-api, cùng 27 mutant, cùng định nghĩa "phát hiện".

Schemathesis tự sinh request từ OpenAPI (fuzz + kiểm tra phản hồi theo spec) nên trên bản CHUẨN nó vẫn báo vài lỗi (phản hồi lỗi
không khớp spec...). Vì vậy: baseline = hợp của các lỗi (thao tác, mã ST) ở 2 lượt chạy bản chuẩn (seed 1 và 2); mutant bị
PHÁT HIỆN khi lượt chạy có lỗi MỚI (thao tác, mã) không nằm trong baseline.

Cần: pip install schemathesis (nên trong venv riêng) và mutant-api đang chạy với MUTANT_CONTROL=true.
    python scripts/eval/run-schemathesis.py --st path/to/st --out docs/eval/schemathesis-2026-10-21.json
Giới hạn công bằng: Schemathesis dùng 1 token HR (không có khái niệm vai/ngữ cảnh nghiệp vụ), không dùng AI, không có test gốc do người viết.
"""
import argparse
import glob
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import urllib.request
import xml.etree.ElementTree as ET


def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        text = r.read().decode()
    return json.loads(text) if text else None


def run_st(args, token, seed):
    report_dir = tempfile.mkdtemp(prefix="st-rep-")
    env = dict(os.environ, PYTHONUTF8="1", PYTHONIOENCODING="utf-8")
    proc = subprocess.run(
        [args.st, "run", args.target + "/v3/api-docs", "--url", args.target, "-H", f"Authorization: Bearer {token}",
         "--max-examples", str(args.examples), "--seed", str(seed), "--workers", "1", "--no-color",
         "--report", "junit", "--report-dir", report_dir],
        capture_output=True, text=True, encoding="utf-8", errors="replace", env=env, timeout=600)
    files = glob.glob(os.path.join(report_dir, "*.xml"))
    if not files:
        raise SystemExit("Schemathesis không tạo báo cáo JUnit:\n" + (proc.stdout + proc.stderr)[-800:])
    failures = set()
    for tc in ET.parse(files[0]).getroot().iter("testcase"):
        for child in tc:
            if child.tag in ("failure", "error"):
                failures.add((tc.attrib.get("name"), child.attrib.get("message") or child.attrib.get("type")))
    return failures


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--st", required=True, help="đường dẫn tới lệnh st (Schemathesis)")
    ap.add_argument("--target", default="http://localhost:8083")
    ap.add_argument("--examples", type=int, default=25, help="--max-examples của Schemathesis")
    ap.add_argument("--out", default="docs/eval/schemathesis.json")
    args = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    catalog = call("GET", args.target + "/__mutants")["catalog"]
    login = call("POST", args.target + "/auth/login", {"email": "hr1@demo.local", "password": "Passw0rd!"})
    token = login["token"]

    baseline, baseline_runs = set(), []
    for seed in (1, 2):
        call("POST", args.target + "/__reset")
        f = run_st(args, token, seed)
        baseline_runs.append(len(f))
        baseline |= f
    print(f"Bản chuẩn: seed 1 và 2 báo {baseline_runs[0]} và {baseline_runs[1]} lỗi (hợp {len(baseline)} lỗi (thao tác, mã) = baseline)")

    results = []
    for m in catalog:
        call("POST", args.target + "/__reset")
        call("POST", args.target + "/__mutants", {"enabled": [m["id"]]})
        failures = run_st(args, token, 1)
        new = sorted(failures - baseline)
        results.append({"id": m["id"], "category": m["category"], "detected": bool(new), "newFailures": [list(x) for x in new][:6]})
        print(f"  {m['id']} {m['category']:<16} -> {'PHÁT HIỆN' if new else 'bỏ lọt  '} {new[:2] if new else ''}")
    call("POST", args.target + "/__reset")

    detected = sum(1 for r in results if r["detected"])
    by_cat = {}
    for r in results:
        d, n = by_cat.get(r["category"], (0, 0))
        by_cat[r["category"]] = (d + (1 if r["detected"] else 0), n + 1)
    print(f"\nSchemathesis: phát hiện {detected}/{len(results)} mutant; theo loại: " + ", ".join(f"{k} {d}/{n}" for k, (d, n) in sorted(by_cat.items())))
    pathlib.Path(args.out).write_text(json.dumps({"tool": "schemathesis", "examples": args.examples, "baselineFailureCounts": baseline_runs,
                                                  "baselineUnion": len(baseline), "mutants": results, "detected": detected,
                                                  "total": len(results)}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Đã ghi", args.out)


if __name__ == "__main__":
    main()
