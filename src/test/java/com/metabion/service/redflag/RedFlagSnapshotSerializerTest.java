package com.metabion.service.redflag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.exception.RedFlagSnapshotException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedFlagSnapshotSerializerTest {

    @Test
    void serializesExactSingleFactJsonWithCanonicalDecimalText() {
        var serializer = serializer();
        var fact = matched(RedFlagSourceType.LAB_RESULT_SET, 91L, LocalDate.of(2026, 7, 28),
                new RedFlagFact("lab.CRP", new BigDecimal("312.000000"), null, "mg/L"));

        assertThat(serializer.serialize(List.of(fact))).isEqualTo(
                "{\"facts\":[{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":91,\"factKey\":\"lab.CRP\","
                        + "\"observedOn\":\"2026-07-28\",\"decimalValue\":\"312\",\"textValue\":null,"
                        + "\"unit\":\"mg/L\"}]}");
    }

    @Test
    void sortsFactsBySourceNameKeyDateAndSourceId() {
        var serializer = serializer();
        var facts = List.of(
                matched(RedFlagSourceType.SYMPTOM_CHECK_IN, 9L, LocalDate.of(2026, 7, 28),
                        new RedFlagFact("symptom.flare_state", null, "ACTIVE_FLARE", null)),
                matched(RedFlagSourceType.LAB_RESULT_SET, 3L, LocalDate.of(2026, 7, 27),
                        new RedFlagFact("lab.CRP", new BigDecimal("46.20"), null, "mg/L")),
                matched(RedFlagSourceType.LAB_RESULT_SET, 2L, LocalDate.of(2026, 7, 27),
                        new RedFlagFact("lab.CRP", new BigDecimal("45.10"), null, "mg/L")),
                matched(RedFlagSourceType.LAB_RESULT_SET, 1L, LocalDate.of(2026, 7, 28),
                        new RedFlagFact("lab.ALBUMIN", new BigDecimal("29.00"), null, "g/L")));

        assertThat(serializer.serialize(facts)).isEqualTo(
                "{\"facts\":["
                        + "{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":1,\"factKey\":\"lab.ALBUMIN\","
                        + "\"observedOn\":\"2026-07-28\",\"decimalValue\":\"29\",\"textValue\":null,\"unit\":\"g/L\"},"
                        + "{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":2,\"factKey\":\"lab.CRP\","
                        + "\"observedOn\":\"2026-07-27\",\"decimalValue\":\"45.1\",\"textValue\":null,\"unit\":\"mg/L\"},"
                        + "{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":3,\"factKey\":\"lab.CRP\","
                        + "\"observedOn\":\"2026-07-27\",\"decimalValue\":\"46.2\",\"textValue\":null,\"unit\":\"mg/L\"},"
                        + "{\"sourceType\":\"SYMPTOM_CHECK_IN\",\"sourceId\":9,\"factKey\":\"symptom.flare_state\","
                        + "\"observedOn\":\"2026-07-28\",\"decimalValue\":null,\"textValue\":\"ACTIVE_FLARE\",\"unit\":null}]}" );
    }

    @Test
    void deserializesTypedMatchedInputs() {
        var snapshot = serializer().deserialize(
                "{\"facts\":[{\"sourceType\":\"LAB_RESULT_SET\",\"sourceId\":91,"
                        + "\"factKey\":\"lab.CRP\",\"observedOn\":\"2026-07-28\","
                        + "\"decimalValue\":\"312\",\"textValue\":null,\"unit\":\"mg/L\"}]}");

        assertThat(snapshot.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.factKey()).isEqualTo("lab.CRP");
            assertThat(fact.observedOn()).isEqualTo(LocalDate.of(2026, 7, 28));
        });
    }

    @Test
    void corruptSnapshotRaisesOnlySanitizedMessage() {
        assertThatThrownBy(() -> serializer().deserialize("{patient-value"))
                .isInstanceOfSatisfying(RedFlagSnapshotException.class,
                        error -> assertThat(error.getMessage())
                                .isEqualTo("Red-flag snapshot processing failed"));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedFlagSnapshotSerializer serializer() {
        ObjectProvider<ObjectMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(new ObjectMapper());
        return new RedFlagSnapshotSerializer(provider);
    }

    private RedFlagRuleMatch.MatchedFact matched(
            RedFlagSourceType source, Long id, LocalDate date, RedFlagFact fact) {
        return new RedFlagRuleMatch.MatchedFact(source, id, date, fact);
    }
}
