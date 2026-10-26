package com.shoptarget.mutant;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bộ lỗi cài sẵn cho API cửa hàng (hệ thống thứ HAI để đo khả năng bắt lỗi; hệ thống thứ nhất là mutant-api tuyển dụng).
 * Mỗi mutant lật ĐÚNG 1 hành vi so với bản chuẩn; mặc định không bật mutant nào. Điều khiển qua {@link MutantControlController}
 * (chỉ có khi MUTANT_CONTROL=true).
 *
 * <p>{@code expectChecklist} là GIẢ THUYẾT ĐẶT TRƯỚC (viết trước khi đo): checklist tất định (EP/BVA/Security/probe, 0 token) CÓ THỂ bắt lỗi này
 * hay không. false = lỗi cần đọc lại trạng thái sau nhiều bước hoặc kiểm nội dung dữ liệu mà checklist theo spec không có cách biết.
 */
public final class ShopMutants {

    public record Def(String id, String category, boolean expectChecklist, String description) {
    }

    public static final List<Def> CATALOG = List.of(
            new Def("S01", "validation", true, "POST /api/products chấp nhận price âm"),
            new Def("S02", "validation", true, "POST /api/products chấp nhận name rỗng"),
            new Def("S03", "validation", true, "POST /api/products chấp nhận stock âm"),
            new Def("S04", "validation", true, "PUT /api/products/{id} chấp nhận price âm"),
            new Def("S05", "status", true, "GET /api/products/{id} với id không tồn tại trả 200 thay vì 404"),
            new Def("S06", "status", true, "DELETE /api/products/{id} với id không tồn tại trả 204 thay vì 404"),
            new Def("S07", "state", false, "DELETE trả 204 nhưng KHÔNG xoá (sản phẩm vẫn còn)"),
            new Def("S08", "status", true, "POST /api/products trả 200 thay vì 201"),
            new Def("S09", "business", false, "PATCH stock decrease cho phép tồn kho âm (thay vì chặn ở 0)"),
            new Def("S10", "business", false, "GET /api/products bỏ qua tham số minPrice"),
            new Def("S11", "business", false, "GET /api/products lọc name phân biệt hoa thường"),
            new Def("S12", "auth", true, "GET /api/products/{id} không cần token"),
            new Def("S13", "auth", true, "POST /auth/login chấp nhận mọi mật khẩu"),
            new Def("S14", "status", true, "PUT /api/products/{id} với id không tồn tại tự tạo mới thay vì 404"),
            new Def("S15", "robustness", true, "JSON hỏng cú pháp trả 500 thay vì 400"));

    private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();

    private ShopMutants() {
    }

    public static boolean on(String id) {
        return ENABLED.contains(id);
    }

    public static void set(Collection<String> ids) {
        ENABLED.clear();
        ENABLED.addAll(ids);
    }

    public static Set<String> enabled() {
        return Set.copyOf(ENABLED);
    }
}
