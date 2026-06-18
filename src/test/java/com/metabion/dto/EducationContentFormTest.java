package com.metabion.dto;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EducationContentFormTest {

    @Test
    void blankPreallocatedLessonRowsDoNotFailFormValidationAndAreFiltered() {
        var form = new EducationContentForm();
        form.setSlug("hydration");
        form.setTopic("IBD");
        form.setEnglishTitle("Hydration");
        form.setEnglishSummary("Hydration basics");
        form.ensureRows(2);

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(form);

            assertThat(violations).isEmpty();
        }
        assertThat(form.toLessonRequests()).isEmpty();
    }

    @Test
    void partiallyPopulatedLessonRowsFailFormValidation() {
        var form = new EducationContentForm();
        form.setSlug("hydration");
        form.setTopic("IBD");
        form.setEnglishTitle("Hydration");
        form.setEnglishSummary("Hydration basics");

        var lesson = new EducationContentForm.LessonRow();
        lesson.setSlug("water");
        form.setLessons(List.of(lesson));

        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            var violations = validatorFactory.getValidator().validate(form);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("lessons[0].completeOrBlank");
        }
    }
}
