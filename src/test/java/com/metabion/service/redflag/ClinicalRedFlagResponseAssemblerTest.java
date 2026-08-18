package com.metabion.service.redflag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClinicalRedFlagResponseAssemblerTest {

    @Test
    void clinicalWriteProjectionIncludesRuleVersionAndMatchedFacts() {
        var detectedAt = Instant.parse("2026-07-29T12:34:56Z");
        var matchedInputsJson = "{\"facts\":[{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":91,"
                + "\"factKey\":\"lab.CRP\",\"observedOn\":\"2026-07-28\","
                + "\"decimalValue\":\"312\",\"textValue\":null,\"unit\":\"mg/L\"}]}";
        var outcome = new RedFlagEvaluationOutcome(
                RedFlagSeverity.EMERGENCY,
                List.of(new RedFlagEvaluationOutcome.Flag(
                        701L, "LAB_CRP_HIGH", RedFlagSeverity.EMERGENCY, detectedAt,
                        RedFlagSourceType.LAB_RESULT_SET, 91L, 3, matchedInputsJson)),
                List.of("LAB_OLD_RULE"));

        var response = assembler().outcome(outcome);

        assertThat(response.highestSeverity()).isEqualTo(RedFlagSeverity.EMERGENCY);
        assertThat(response.currentFlags()).singleElement().satisfies(flag -> {
            assertThat(flag.eventId()).isEqualTo(701L);
            assertThat(flag.ruleKey()).isEqualTo("LAB_CRP_HIGH");
            assertThat(flag.ruleVersion()).isEqualTo(3);
            assertThat(flag.matchedInputs().facts()).singleElement().satisfies(fact -> {
                assertThat(fact.sourceType()).isEqualTo(RedFlagSourceType.LAB_RESULT_SET);
                assertThat(fact.sourceId()).isEqualTo(91L);
                assertThat(fact.factKey()).isEqualTo("lab.CRP");
                assertThat(fact.observedOn()).isEqualTo(LocalDate.of(2026, 7, 28));
                assertThat(fact.decimalValue()).isEqualTo("312");
                assertThat(fact.unit()).isEqualTo("mg/L");
            });
        });
        assertThat(response.clearedRuleKeys()).containsExactly("LAB_OLD_RULE");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ClinicalRedFlagResponseAssembler assembler() {
        ObjectProvider<ObjectMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(new ObjectMapper());
        return new ClinicalRedFlagResponseAssembler(new RedFlagSnapshotSerializer(provider));
    }
}
