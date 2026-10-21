package com.mutantapi.mutant;

/**
 * Danh muc loi cai san (mutant): 27 loi dau (M01-M27) la so lieu do trong bao cao, M28+ bo sung de test tay. Moi mutant la 1 co bat/tat luc chay, rẽ nhanh dung 1 cho trong code
 * (xem Mutants.on). Ban chuan = khong mutant nao bat.
 *
 * expectChecklist = GIA THUYET truoc khi do: bo kiem tra tat dinh EP/BVA (Positive/Negative/Boundary/
 * Security) co the bat duoc mutant nay khong. Day la gia thuyet de doi chieu voi ket qua do that, KHONG phai
 * ket luan - ket qua do that nam trong docs/DANH_GIA.md.
 */
public enum MutantFlag {

    // ---- Bo validate ----
    M01(Category.VALIDATION, "POST /api/jobs", "Thieu title van tao duoc job (201)", true),
    M02(Category.VALIDATION, "POST /api/jobs", "title chi gom khoang trang / rong van duoc nhan", true),
    M03(Category.VALIDATION, "POST /api/jobs", "title dai bat ky (khong gioi han toi da 200) van duoc nhan", true),
    M04(Category.VALIDATION, "POST /api/jobs", "salaryCurrency dai hon 3 ky tu van duoc nhan (bo gioi han toi da)", true),
    M05(Category.VALIDATION, "POST /api/jobs", "jobType ngoai danh sach enum bi bo qua, mac dinh FULL_TIME (khong tra 400)", true),

    // ---- Sai bien (off-by-one) ----
    M06(Category.BOUNDARY, "POST /api/jobs", "salaryMin = -1 van duoc nhan (bien duoi 0 thanh -1)", true),
    M07(Category.BOUNDARY, "POST /api/jobs", "title dai dung 200 ky tu (bien tren hop le) bi tu choi nham (gioi han toi da 200 thanh 199)", true),
    M08(Category.BOUNDARY, "PUT /api/jobs/{id}/rubric", "weight = 0 van duoc nhan (bien duoi 0.01 thanh 0; tong van phai 100 nen quan sat duoc)", false),
    M09(Category.BOUNDARY, "PUT /api/jobs/{id}/rubric", "maxScore = 0 van duoc nhan (bien duoi 1 thanh 0)", false),
    M10(Category.BOUNDARY, "POST /api/jobs", "salaryCurrency dai 2 ky tu van duoc nhan (bien duoi 3 thanh 2)", true),

    // ---- Sai ma trang thai ----
    M11(Category.STATUS_CODE, "POST /api/jobs", "Loi validate tra 200 kem than loi thay vi 400", true),
    M12(Category.STATUS_CODE, "GET /api/jobs/{id}", "Job khong ton tai tra 500 thay vi 404", false),
    M13(Category.STATUS_CODE, "PUT /api/jobs/{id}/rubric", "Du lieu sai tra 401 thay vi 400 (nhan dien nham la loi xac thuc)", true),

    // ---- Thieu phan quyen ----
    M14(Category.AUTHORIZATION, "POST /api/jobs", "CANDIDATE tao duoc job (thieu kiem tra vai tro HR)", false),
    M15(Category.AUTHORIZATION, "PUT /api/jobs/{id}", "HR khac sua duoc job cua HR khac (thieu kiem tra chu so huu)", false),
    M16(Category.AUTHORIZATION, "GET /api/applications/{id}", "CANDIDATE xem duoc don ung tuyen cua nguoi khac", false),
    M17(Category.AUTHORIZATION, "PATCH /api/applications/{id}/status", "HR khac doi duoc trang thai don cua job khong thuoc minh", false),
    M18(Category.AUTHORIZATION, "GET /api/jobs/{id}", "Khong can token van doc duoc job (bo kiem tra xac thuc)", true),

    // ---- Mass-assignment ----
    M19(Category.MASS_ASSIGNMENT, "POST /api/jobs", "Nhan ownerId tu body (gan chu so huu tuy y)", true),
    M20(Category.MASS_ASSIGNMENT, "PUT /api/jobs/{id}", "Nhan status tu body (tu dong/mo job qua truong khong khai bao)", true),

    // ---- Lo du lieu nhay cam ----
    M21(Category.DATA_EXPOSURE, "GET /api/users/me", "Tra them passwordHash trong response", true),
    M22(Category.DATA_EXPOSURE, "POST /api/jobs", "Response loi 400 kem thong tin noi bo (ten class, SQL)", true),

    // ---- Vi pham luat nghiep vu ----
    M23(Category.BUSINESS_RULE, "POST /api/jobs", "salaryMin > salaryMax van duoc nhan", false),
    M24(Category.BUSINESS_RULE, "POST /api/jobs", "deadline o qua khu van duoc nhan", false),
    M25(Category.BUSINESS_RULE, "PUT /api/jobs/{id}/rubric", "Tong weight khac 100 van duoc nhan", false),
    M26(Category.BUSINESS_RULE, "PATCH /api/applications/{id}/status", "Nhay PENDING -> HIRED (bo qua INTERVIEW_INVITED) van duoc nhan", false),
    M27(Category.BUSINESS_RULE, "POST /api/jobs/{id}/applications", "Nop don trung (cung ung vien, cung job) van duoc nhan", false),

    // ---- BO SUNG sau dot do 27 loi: moi endpoint co it nhat 3 loi de TEST TAY. CHUA do trong docs/DANH_GIA.md (inReport() = false). ----
    M28(Category.AUTHORIZATION, "POST /auth/login", "Sai mat khau van dang nhap duoc (chi kiem tra email)", false),
    M29(Category.STATUS_CODE, "POST /auth/login", "Email khong ton tai tra 500 thay vi 401", false),
    M30(Category.DATA_EXPOSURE, "POST /auth/login", "Response dang nhap tra them passwordHash", true),
    M31(Category.AUTHORIZATION, "GET /api/users/me", "Khong can token van tra 200 (bo kiem tra xac thuc)", true),
    M32(Category.DATA_EXPOSURE, "GET /api/users/me", "Tra them danh sach email cua cac tai khoan khac (allUsers)", false),
    M33(Category.STATUS_CODE, "GET /api/jobs/{id}", "Tin da xoa mem van xem duoc (200 thay vi 404)", false),
    M34(Category.STATUS_CODE, "PUT /api/jobs/{id}", "Tin da xoa mem van sua duoc (200 thay vi 404)", false),
    M35(Category.AUTHORIZATION, "DELETE /api/jobs/{id}", "HR khac xoa duoc tin cua HR khac (thieu kiem tra chu so huu)", false),
    M36(Category.STATUS_CODE, "DELETE /api/jobs/{id}", "Xoa thanh cong tra 200 thay vi 204", true),
    M37(Category.STATUS_CODE, "DELETE /api/jobs/{id}", "Xoa tin da xoa roi van tra 204 (dang le 404)", false),
    M38(Category.AUTHORIZATION, "POST /api/jobs/{jobId}/applications", "HR (khong phai ung vien) nop don duoc (thieu kiem tra vai tro)", true),
    M39(Category.BUSINESS_RULE, "POST /api/jobs/{jobId}/applications", "consent = false van duoc nhan don", false),
    M40(Category.AUTHORIZATION, "GET /api/applications/{id}", "HR khac xem duoc don cua tin khong thuoc minh", false),
    M41(Category.STATUS_CODE, "GET /api/applications/{id}", "Don khong ton tai tra 500 thay vi 404", true),
    M42(Category.VALIDATION, "PATCH /api/applications/{id}/status", "Trang thai ngoai enum bi bo qua, van tra 200 (khong tra 400)", true);

    /** So mutant nam trong so lieu do cua docs/DANH_GIA.md (M01..M27); cac mutant sau chi de test tay. */
    public static final int REPORT_COUNT = 27;

    public boolean inReport() {
        return ordinal() < REPORT_COUNT;
    }

    public enum Category {
        VALIDATION("Bo validate"),
        BOUNDARY("Sai bien"),
        STATUS_CODE("Sai ma trang thai"),
        AUTHORIZATION("Thieu phan quyen"),
        MASS_ASSIGNMENT("Mass-assignment"),
        DATA_EXPOSURE("Lo du lieu nhay cam"),
        BUSINESS_RULE("Vi pham luat nghiep vu");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Category category;
    private final String endpoint;
    private final String description;
    private final boolean expectChecklist;

    MutantFlag(Category category, String endpoint, String description, boolean expectChecklist) {
        this.category = category;
        this.endpoint = endpoint;
        this.description = description;
        this.expectChecklist = expectChecklist;
    }

    public Category category() {
        return category;
    }

    public String endpoint() {
        return endpoint;
    }

    public String description() {
        return description;
    }

    public boolean expectChecklist() {
        return expectChecklist;
    }
}
