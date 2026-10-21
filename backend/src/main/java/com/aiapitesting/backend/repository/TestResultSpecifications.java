package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.FailureCause;
import com.aiapitesting.backend.entity.TestCase;
import com.aiapitesting.backend.entity.TestExecution;
import com.aiapitesting.backend.entity.TestResult;
import com.aiapitesting.backend.entity.TestResultStatus;
import com.aiapitesting.backend.service.PagingSupport;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kết quả của 1 lần chạy theo trang (Module 17 G4). Thứ tự cố định: thời điểm tạo test case rồi {@code id} kết quả làm khoá phụ -
 * thứ tự cũ chỉ có createdAt (không duy nhất) nên giữa các trang có thể chồng/lọt dòng.
 */
public final class TestResultSpecifications {

    public record Filter(TestResultStatus status, FailureCause failureCause, UUID endpointId, String q) {
    }

    private TestResultSpecifications() {
    }

    @SuppressWarnings("unchecked")
    public static Specification<TestResult> forExecution(TestExecution execution, Filter f) {
        return (root, query, cb) -> {
            // Chỉ truy vấn lấy ENTITY mới fetch + sắp xếp; mọi truy vấn khác (đếm, exists...) chỉ join.
            boolean dataQuery = TestResult.class.equals(query.getResultType());
            Join<TestResult, TestCase> testCase;
            Join<TestCase, Endpoint> endpoint;
            if (!dataQuery) {
                testCase = root.join("testCase", JoinType.INNER);
                endpoint = testCase.join("endpoint", JoinType.INNER);
            } else {
                // TestResultResponse.from đọc testCase và testCase.endpoint sau khi session đóng: phải fetch cả hai (to-one nên an toàn với phân trang).
                testCase = (Join<TestResult, TestCase>) (Join<?, ?>) root.fetch("testCase", JoinType.INNER);
                endpoint = (Join<TestCase, Endpoint>) (Join<?, ?>) testCase.fetch("endpoint", JoinType.INNER);
                query.orderBy(cb.asc(testCase.get("createdAt")), cb.asc(root.get("id")));
            }

            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("execution"), execution));
            if (f.status() != null) {
                predicates.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.failureCause() != null) {
                predicates.add(cb.equal(root.get("failureCause"), f.failureCause()));
            }
            if (f.endpointId() != null) {
                predicates.add(cb.equal(endpoint.get("id"), f.endpointId()));
            }
            if (f.q() != null) {
                String pattern = "%" + PagingSupport.escapeLike(f.q().toLowerCase(java.util.Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(testCase.get("name")), pattern, '\\'),
                        cb.like(cb.lower(endpoint.get("path")), pattern, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
