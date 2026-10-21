#!/usr/bin/env python3
"""
Sinh các bảng Markdown cho docs/DANH_GIA.md TRỰC TIẾP từ file kết quả JSON của scripts/eval-mutants.py - không gõ số bằng tay,
chạy lại ra đúng số. Chỉ dùng thư viện chuẩn.

    python scripts/eval/make-report-tables.py docs/eval/results-A-run12.json docs/eval/results-B-merged.json
"""
import json
import pathlib
import statistics
import sys

AMBIGUOUS_NOTE = "phát hiện = test Pass->Fail khi bật mutant (không do môi trường/nhiễu); gán đúng = nhãn nguyên nhân của hệ thống chắc là lỗi API"


def load(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def cfg_runs(data, cfg):
    return [r["configs"][cfg] for r in data["runs"] if cfg in r["configs"]]


def counts(run):
    caught = sum(1 for m in run["mutants"] if m["outcome"] == "caught")
    detected = sum(1 for m in run["mutants"] if m["outcome"] in ("caught", "suspect"))
    flagged = sum(1 for m in run["mutants"] if m["outcome"] == "flagged")
    return caught, detected, flagged


def ms(values, digits=1):
    if len(values) == 1:
        return f"{values[0]:.{digits}f}"
    return f"{statistics.mean(values):.{digits}f} ± {statistics.pstdev(values):.{digits}f}"


def overview(sources):
    print("### Tổng quan theo cấu hình\n")
    print("| Cấu hình | Số lượt | Mutant phát hiện (/27) | Gán đúng lỗi API (/27) | Chỉ có cảnh báo bảo mật | Fail giả trên bản chuẩn | Token sinh test / lượt | Thời gian sinh / lượt |")
    print("|---|---|---|---|---|---|---|---|")
    for label, data, cfg in sources:
        runs = cfg_runs(data, cfg)
        c = [counts(r) for r in runs]
        ff = [100 * r["falseFailures"] / r["tests"] for r in runs]
        gen = [r["generation"] for r in data["runs"] if cfg in r["configs"]]
        tokens = [g["tokens"] for g in gen]
        secs = [g["seconds"] for g in gen]
        print(f"| {label} | {len(runs)} | {ms([x[1] for x in c])} | {ms([x[0] for x in c])} | {ms([x[2] for x in c])} | "
              f"{ms(ff)}% | {ms(tokens, 0)} | {ms(secs, 0)}s |")
    print(f"\n*{AMBIGUOUS_NOTE}. Nhiều lượt: trung bình ± độ lệch chuẩn tổng thể (pstdev).*\n")


def by_category(label, data, cfg):
    runs = cfg_runs(data, cfg)
    cats = sorted({m["category"] for m in runs[0]["mutants"]})
    print(f"### Theo loại lỗi - {label}\n")
    print("| Loại | Số mutant | Phát hiện (mỗi lượt) | Gán đúng lỗi API (mỗi lượt) |")
    print("|---|---|---|---|")
    for cat in cats + ["TỔNG"]:
        det, att = [], []
        for r in runs:
            sel = [m for m in r["mutants"] if cat == "TỔNG" or m["category"] == cat]
            det.append(sum(1 for m in sel if m["outcome"] in ("caught", "suspect")))
            att.append(sum(1 for m in sel if m["outcome"] == "caught"))
        n = len([m for m in runs[0]["mutants"] if cat == "TỔNG" or m["category"] == cat])
        print(f"| {cat} | {n} | {' / '.join(map(str, det))} | {' / '.join(map(str, att))} |")
    print()


def per_mutant(sources):
    """Mỗi mutant: ký hiệu theo từng cấu hình = số lượt phát hiện / số lượt (A tất định nên 1 hoặc 0)."""
    catalog = {m["id"]: m for m in sources[0][1]["mutantCatalog"]}
    print("### Từng mutant\n")
    heads = " | ".join(f"{label}" for label, _, _ in sources)
    print(f"| Mutant | Loại | Endpoint | Mô tả | Giả thuyết trước đo (checklist bắt?) | {heads} |")
    print("|---|---|---|---|---|" + "---|" * len(sources))
    for mid in sorted(catalog):
        cells = []
        for label, data, cfg in sources:
            runs = cfg_runs(data, cfg)
            states = []
            for r in runs:
                m = next(x for x in r["mutants"] if x["id"] == mid)
                states.append(m["outcome"])
            det = sum(1 for s in states if s in ("caught", "suspect"))
            att = sum(1 for s in states if s == "caught")
            flag = sum(1 for s in states if s == "flagged")
            text = f"{det}/{len(states)}"
            if att:
                text += f" (gán đúng {att})"
            if flag:
                text += f" (cảnh báo {flag})"
            cells.append(text)
        c = catalog[mid]
        print(f"| {mid} | {c['categoryLabel']} | `{c['endpoint']}` | {c['description']} | {'có' if c['expectChecklist'] else 'không'} | " + " | ".join(cells) + " |")
    print()


def false_failures(label, data, cfg):
    runs = cfg_runs(data, cfg)
    total = {}
    for r in runs:
        for k, v in r["falseFailureCauses"].items():
            total[k] = total.get(k, 0) + v
    print(f"Nguyên nhân Fail giả trên bản chuẩn - {label} (cộng dồn {len(runs)} lượt): " +
          ", ".join(f"{k} {v}" for k, v in sorted(total.items(), key=lambda kv: -kv[1])) + "\n")


def hypothesis(sources):
    """Giả thuyết 'checklist bắt được' có đúng không (đối chiếu với cấu hình đầu tiên là cấu hình không AI)."""
    label, data, cfg = sources[0]
    run = cfg_runs(data, cfg)[0]
    ok = [m for m in run["mutants"] if m["expectChecklist"] and m["outcome"] in ("caught", "suspect", "flagged")]
    miss = [m["id"] for m in run["mutants"] if m["expectChecklist"] and m["outcome"] == "missed"]
    wrong = [m["id"] for m in run["mutants"] if not m["expectChecklist"] and m["outcome"] in ("caught", "suspect", "flagged")]
    n_exp = sum(1 for m in run["mutants"] if m["expectChecklist"])
    print(f"Giả thuyết trước đo ({label}): dự đoán checklist bắt {n_exp} mutant; đúng {len(ok)}/{n_exp}; dự đoán bắt nhưng bỏ lọt: {', '.join(miss) or 'không'}; "
          f"dự đoán không bắt nhưng vẫn bắt được: {', '.join(wrong) or 'không'}.\n")


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    a, b = load(sys.argv[1]), load(sys.argv[2])
    sources = [("A - checklist (0 token)", a, "A"), ("B - hybrid (AI sinh + nghiệp vụ)", b, "B"), ("C - chỉ AI nghiệp vụ (xấp xỉ)", b, "C")]
    overview(sources)
    by_category("A", a, "A")
    by_category("B", b, "B")
    by_category("C", b, "C")
    per_mutant(sources)
    hypothesis(sources)
    for label, data, cfg in sources:
        false_failures(label, data, cfg)
    env = b["environment"]
    print(f"Môi trường đo: model `{env['model']}`, {env['promptFiles']} file prompt (hash {env['promptHash']}), commit {str(env['gitCommit'])[:10]}"
          f"{' (có thay đổi chưa commit)' if env.get('gitDirty') else ''}, ngày {env['date'][:10]}.")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    main()
