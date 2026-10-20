package com.aiapitesting.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Dự án không dùng Flyway và {@code ddl-auto=update} KHÔNG đổi kiểu cột đã có. Hibernate 6 + MySQL tạo cột enum Java thành kiểu ENUM
 * gốc của MySQL, nên thêm 1 giá trị mới vào enum Java (vd kiểu xác thực BASIC_AUTH) làm mọi lệnh ghi lỗi "Data truncated" (HTTP 500) trên
 * CSDL cũ. Khi khởi động, chuyển mọi cột ENUM gốc của MySQL sang VARCHAR (giữ nguyên dữ liệu, NULL/DEFAULT) để thêm giá trị enum về sau
 * không cần migration thủ công. Chỉ chạy với MySQL; lỗi từng cột chỉ ghi log, không chặn khởi động.
 */
@Slf4j
@Component
@Order(0)
public class EnumColumnUpgrade implements ApplicationRunner {

    private static final int MIN_LENGTH = 40;

    private final JdbcTemplate jdbc;

    public EnumColumnUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> columns;
        try {
            columns = jdbc.queryForList("SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND DATA_TYPE = 'enum'");
        } catch (Exception e) {
            log.debug("Bo qua nang cap cot enum (khong phai MySQL hoac khong doc duoc information_schema): {}", e.toString());
            return;
        }
        for (Map<String, Object> column : columns) {
            String table = String.valueOf(column.get("TABLE_NAME"));
            String name = String.valueOf(column.get("COLUMN_NAME"));
            if (!isSafeIdentifier(table) || !isSafeIdentifier(name)) {
                continue;
            }
            boolean nullable = "YES".equalsIgnoreCase(String.valueOf(column.get("IS_NULLABLE")));
            Object defaultValue = column.get("COLUMN_DEFAULT");
            StringBuilder sql = new StringBuilder("ALTER TABLE `").append(table).append("` MODIFY `").append(name)
                    .append("` VARCHAR(").append(MIN_LENGTH).append(')').append(nullable ? " NULL" : " NOT NULL");
            if (defaultValue != null && isSafeIdentifier(String.valueOf(defaultValue))) {
                sql.append(" DEFAULT '").append(defaultValue).append('\'');
            }
            try {
                jdbc.execute(sql.toString());
                log.info("Da chuyen cot enum {}.{} sang VARCHAR({})", table, name, MIN_LENGTH);
            } catch (Exception e) {
                log.warn("Khong chuyen duoc cot enum {}.{}: {}", table, name, e.toString());
            }
        }
    }

    /** Chỉ chữ/số/_ - tên bảng/cột và giá trị mặc định đi vào câu lệnh DDL nên phải chặt. */
    static boolean isSafeIdentifier(String value) {
        return value != null && !value.isEmpty() && value.matches("[A-Za-z0-9_]+");
    }
}
