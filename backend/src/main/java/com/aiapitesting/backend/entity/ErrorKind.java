package com.aiapitesting.backend.entity;

/**
 * Module 16 - loại lỗi hạ tầng khi gọi target API (chỉ có với TestResult ERROR), tự phân loại theo loại
 * exception thay vì đọc chuỗi errorMessage - mỗi thư viện HTTP viết thông báo một kiểu nên khớp chữ
 * không ổn định. Không phụ thuộc target cụ thể nào.
 */
public enum ErrorKind {
    /** Hết thời gian kết nối hoặc chờ response. */
    TIMEOUT("hết thời gian chờ"),
    /** Không kết nối được / bị ngắt kết nối (target sập, sai cổng, tường lửa). */
    CONNECTION("không kết nối được tới hệ thống đích"),
    /** Không phân giải được tên miền (sai host). */
    DNS("không phân giải được tên miền"),
    /** Lỗi khác khi gọi (SSL, response sai định dạng...). */
    OTHER("lỗi khác khi gọi hệ thống đích");

    private final String label;

    ErrorKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
