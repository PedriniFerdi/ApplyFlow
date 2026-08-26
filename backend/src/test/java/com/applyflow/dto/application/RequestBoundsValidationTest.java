package com.applyflow.dto.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.applyflow.dto.catalog.NewCompanyRequest;
import com.applyflow.entity.ApplicationStatus;
import com.applyflow.entity.CompanyType;
import com.applyflow.entity.WorkMode;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class RequestBoundsValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsExactUnicodeAndCollectionLimits() {
        CreateJobApplicationRequest request = request("🚀".repeat(180), "🚀".repeat(5000),
                Collections.nCopies(50, 1L));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsEachOverLimitField() {
        CreateJobApplicationRequest request = request("🚀".repeat(181), "🚀".repeat(5001),
                Collections.nCopies(51, 1L));

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("positionTitle", "notes", "technologyIds");
    }

    private CreateJobApplicationRequest request(String title, String notes, List<Long> technologies) {
        return new CreateJobApplicationRequest(
                null, new NewCompanyRequest("Acme", null, CompanyType.OTHER, null), title, null, null,
                ApplicationStatus.BOOKMARKED, 1L, WorkMode.REMOTE, null, null, null, null, null, notes,
                technologies);
    }
}
