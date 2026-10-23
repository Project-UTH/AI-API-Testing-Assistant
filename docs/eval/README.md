# docs/eval - dữ liệu đánh giá (đọc trước khi code)

Thư mục này chứa dữ liệu dùng để ĐO hệ thống. Một số file là "đề thi kín". Nếu người hoặc agent đang code hệ thống đọc các file này rồi chỉnh hệ thống theo, số đo sẽ bị thổi phồng (overfitting). Xem roadmap 17q mục G0 và G5.

## KHÔNG đọc khi code hoặc chỉnh hệ thống (backend, frontend, prompt `.st`)

| File | Vì sao giữ kín |
|---|---|
| `heldout-mutants.md` | Bộ lỗi held-out H01-H15. Chỉ dùng để đo 1 lần ở G5. |
| `srs-mutant-api.answer-key.md` | Đáp án trích luật (precision/recall). Không được đưa cho AI trích luật. |
| `ground-truth-rules.md` | 86 luật chuẩn của mutant-api. Là nguồn của SRS và bộ held-out. |
| `mutant-api/src/.../MutantFlag.java` (các cờ `H01`-`H15`) và trang `/__mutants` khi đã bật cờ H | Mô tả bộ held-out. |

## Được dùng

- `srs-mutant-api.md`: tài liệu nghiệp vụ (đầu vào của AI trích luật ở G3.3).
- `rule-template-catalog.md`: danh mục mẫu luật, đã chốt trước khi có bộ held-out.
- `results-*.json`: kết quả đo. Sau khi sửa script (commit `de30350`), file kết quả chỉ chứa catalog của các mutant đã đo.

## Quy tắc đo

- `scripts/eval-mutants.py` mặc định LOẠI bộ held-out. Chỉ thêm `--include-heldout` ở lần đo G5.
- Báo cáo G5 phải tách 3 nhóm:
  - bộ phát triển M01-M42;
  - held-out trùng hành vi với M: H02≡M08, H06≡M38, H13≡M16, H14≡M21, H10 chồng một phần M25;
  - held-out "mới".
- Trong nhóm held-out mới, tách tiếp:
  - H vi phạm luật có trong SRS;
  - H vi phạm luật bị bỏ khỏi SRS (nhóm đối chứng).
- Ghi các hạn chế sau vào `docs/DANH_GIA.md`:
  - người viết đề là AI cùng họ với AI trong hệ thống (Claude);
  - người code hệ thống đã thấy bảng tóm tắt H01-H15 lúc nhận kết quả;
  - agent rút luật chuẩn có đọc mã nguồn mutant-api (có mô tả M01-M42);
  - SRS và bộ held-out cùng rút từ 86 luật chuẩn.
