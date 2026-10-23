package com.aiapitesting.backend.repository;

import com.aiapitesting.backend.entity.BusinessRule;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.RuleStatus;
import com.aiapitesting.backend.entity.RuleTemplate;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 17q G3 - luật nghiệp vụ theo project. Luật bị loại KHÔNG bị xoá nên mọi truy vấn đều thấy chúng (lọc bằng status). */
public interface BusinessRuleRepository extends JpaRepository<BusinessRule, UUID>, JpaSpecificationExecutor<BusinessRule> {

    Optional<BusinessRule> findByIdAndProject(UUID id, Project project);

    List<BusinessRule> findAllByProject(Project project);

    /** Xoá mọi luật của project (xoá project). @Modifying: derived delete vỡ khi dòng đã bị xoá bởi luồng khác. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM BusinessRule r WHERE r.project = :project")
    void deleteAllByProject(@org.springframework.data.repository.query.Param("project") Project project);

    boolean existsByProjectAndFingerprint(Project project, String fingerprint);

    Optional<BusinessRule> findByProjectAndFingerprint(Project project, String fingerprint);

    static Specification<BusinessRule> filter(Project project, String endpointKey, UUID endpointId, RuleStatus status, RuleSource source,
                                              RuleTemplate template) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project"), project));
            if (endpointKey != null) {
                predicates.add(cb.equal(root.get("endpointKey"), endpointKey));
            }
            if (endpointId != null) {
                predicates.add(cb.equal(root.get("endpointId"), endpointId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (source != null) {
                predicates.add(cb.equal(root.get("source"), source));
            }
            if (template != null) {
                predicates.add(cb.equal(root.get("template"), template));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
