package com.applyflow.repository;

import java.util.Collection;

import org.springframework.data.jpa.domain.Specification;

import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.JobApplication;

public final class JobApplicationSpecifications {

    private JobApplicationSpecifications() {
    }

    public static Specification<JobApplication> ownedBy(Long ownerId) {
        return (root, query, builder) -> builder.equal(root.get("owner").get("id"), ownerId);
    }

    public static Specification<JobApplication> hasStatus(Collection<ApplicationStatus> statuses) {
        return (root, query, builder) -> statuses == null || statuses.isEmpty()
                ? builder.conjunction()
                : root.get("status").in(statuses);
    }

    public static Specification<JobApplication> hasSource(Long sourceId) {
        return (root, query, builder) -> sourceId == null
                ? builder.conjunction()
                : builder.equal(root.get("source").get("id"), sourceId);
    }

    public static Specification<JobApplication> hasCompany(Long companyId) {
        return (root, query, builder) -> companyId == null
                ? builder.conjunction()
                : builder.equal(root.get("company").get("id"), companyId);
    }

    public static Specification<JobApplication> hasTechnology(Long technologyId) {
        return (root, query, builder) -> {
            if (technologyId == null) {
                return builder.conjunction();
            }
            query.distinct(true);
            return builder.equal(root.join("technologies").get("id"), technologyId);
        };
    }
}
