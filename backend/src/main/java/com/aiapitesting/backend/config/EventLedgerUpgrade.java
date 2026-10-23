package com.aiapitesting.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 17q G3.3 - nâng cấp sổ token AI (bảng test_generation_events) trên CSDL cũ, vì {@code ddl-auto=update} KHÔNG nới NOT NULL của cột đã có:
 * (a) cho phép endpoint_id NULL (sự kiện cấp project / đã tách khỏi endpoint); (b) điền owner_id + project_id cho sự kiện cũ để quota tính theo
 * 2 cột này. Chạy sau khi Hibernate tạo cột. Chỉ MySQL; lỗi chỉ ghi log, không chặn khởi động (cùng tiền lệ EnumColumnUpgrade).
 */
@Slf4j
@Component
@Order(1)
public class EventLedgerUpgrade implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public EventLedgerUpgrade(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String type = jdbc.queryForObject("SELECT COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'test_generation_events' AND COLUMN_NAME = 'endpoint_id' AND IS_NULLABLE = 'NO'", String.class);
            if (type != null && type.matches("[a-zA-Z0-9(), ]+")) {
                jdbc.execute("ALTER TABLE test_generation_events MODIFY endpoint_id " + type + " NULL");
                log.info("Da cho phep endpoint_id NULL o test_generation_events");
            }
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // cột đã nullable (hoặc bảng chưa có) - không cần làm gì
        } catch (Exception e) {
            log.debug("Bo qua nang cap endpoint_id cua so token (khong phai MySQL?): {}", e.toString());
        }
        try {
            int updated = jdbc.update("UPDATE test_generation_events e JOIN endpoints ep ON e.endpoint_id = ep.id "
                    + "JOIN projects p ON ep.project_id = p.id SET e.owner_id = p.owner_id, e.project_id = p.id WHERE e.owner_id IS NULL");
            if (updated > 0) {
                log.info("Da dien owner_id/project_id cho {} su kien token cu", updated);
            }
        } catch (Exception e) {
            log.warn("Khong dien duoc owner_id/project_id cho su kien token cu: {}", e.toString());
        }
    }
}
