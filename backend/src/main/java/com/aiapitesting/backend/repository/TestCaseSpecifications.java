package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestCaseGenerationOrigin;
import com.aiapitesting.backend.entity.TestCaseReliability;
import com.aiapitesting.backend.entity.TestCaseSource;
import com.aiapitesting.backend.service.PagingSupport;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lọc + sắp xếp danh sách test case theo trang (Module 17 G4). Dùng Specification thay cho {@code @Query} có tham số null
 * vì có 7 bộ lọc tuỳ chọn: chỉ thêm đúng điều kiện nào có giá trị. ORDER BY nằm trong spec (không nhận sort từ Pageable) để
 * thứ tự luôn ổn định (có {@code id} làm khoá phụ) và không phát sinh join thứ hai.
 */
public final class TestCaseSpecifications {

    public enum Sort {
        /** Mặc định: nhóm theo endpoint (path, method) rồi theo thời gian tạo - giao diện nhóm các dòng liên tiếp theo endpoint. */
        ENDPOINT, CREATED_ASC, CREATED_DESC, NAME_ASC, NAME_DESC
    }

    public record Filter(String q, String method, UUID endpointId, TestCaseSource source, TestCaseGenerationOrigin generationOrigin,
                         Boolean locked, TestCaseReliability reliability, Sort sort) {
    }

    private TestCaseSpecifications() {
    }

    public static Specification<TestCase> forProject(Project project, Filter f) {
        return (root, query, cb) -> {
            // Chỉ truy vấn lấy ENTITY mới fetch + sắp xếp; mọi truy vấn khác (đếm, exists...) chỉ join - fetch join ở truy vấn không chọn entity gốc sẽ lỗi.
            boolean dataQuery = TestCase.class.equals(query.getResultType());
            Join<TestCase, Endpoint> endpoint;
            if (!dataQuery) {
                endpoint = root.join("endpoint", JoinType.INNER);
            } else {
                // to-one fetch + phân trang an toàn; thứ tự cố định, có id làm khoá phụ để các trang không chồng/lọt dòng.
                endpoint = (Join<TestCase, Endpoint>) (Join<?, ?>) root.fetch("endpoint", JoinType.INNER);
                query.orderBy(orderOf(f.sort(), root, endpoint, cb));
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(endpoint.get("project"), project));
            if (f.endpointId() != null) {
                predicates.add(cb.equal(endpoint.get("id"), f.endpointId()));
            }
            if (f.method() != null) {
                predicates.add(cb.equal(cb.upper(endpoint.get("method")), f.method()));
            }
            if (f.source() != null) {
                predicates.add(cb.equal(root.get("source"), f.source()));
            }
            if (f.generationOrigin() != null) {
                predicates.add(cb.equal(root.get("generationOrigin"), f.generationOrigin()));
            }
            if (f.locked() != null) {
                predicates.add(cb.equal(root.get("locked"), f.locked()));
            }
            if (f.reliability() != null) {
                predicates.add(cb.equal(root.get("reliabilityStatus"), f.reliability()));
            }
            if (f.q() != null) {
                String pattern = "%" + PagingSupport.escapeLike(f.q().toLowerCase(java.util.Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(endpoint.get("path")), pattern, '\\'),
                        cb.like(cb.lower(root.get("resolvedPath")), pattern, '\\'),
                        cb.like(cb.lower(root.get("checklistItem")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static List<Order> orderOf(Sort sort, jakarta.persistence.criteria.Root<TestCase> root, Join<TestCase, Endpoint> endpoint,
                                      CriteriaBuilder cb) {
        List<Order> orders = new ArrayList<>();
        switch (sort == null ? Sort.ENDPOINT : sort) {
            case CREATED_ASC -> orders.add(cb.asc(root.get("createdAt")));
            case CREATED_DESC -> orders.add(cb.desc(root.get("createdAt")));
            case NAME_ASC -> orders.add(cb.asc(cb.lower(root.get("name"))));
            case NAME_DESC -> orders.add(cb.desc(cb.lower(root.get("name"))));
            case ENDPOINT -> {
                orders.add(cb.asc(endpoint.get("path")));
                orders.add(cb.asc(endpoint.get("method")));
                orders.add(cb.asc(root.get("createdAt")));
            }
        }
        orders.add(cb.asc(root.get("id")));
        return orders;
    }
}
