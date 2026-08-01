package com.metabion.service.redflag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class RedFlagSnapshotSerializer {

    private static final Comparator<RedFlagRuleMatch.MatchedFact> FACT_ORDER =
            Comparator.comparing((RedFlagRuleMatch.MatchedFact matched) -> matched.sourceType().name())
                    .thenComparing(matched -> matched.fact().key())
                    .thenComparing(RedFlagRuleMatch.MatchedFact::observedOn)
                    .thenComparing(RedFlagRuleMatch.MatchedFact::sourceId);

    private final ObjectMapper objectMapper;

    public RedFlagSnapshotSerializer(ObjectProvider<ObjectMapper> objectMapperProvider) {
        objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new).copy()
                .registerModule(localDateModule());
    }

    public String serialize(List<RedFlagRuleMatch.MatchedFact> matchedFacts) {
        var facts = matchedFacts.stream().sorted(FACT_ORDER).map(matched -> {
            var fact = matched.fact();
            return new RedFlagMatchedInputSnapshot.Fact(
                    matched.sourceType(), matched.sourceId(), fact.key(), matched.observedOn(),
                    fact.decimalValue() == null ? null
                            : fact.decimalValue().stripTrailingZeros().toPlainString(),
                    fact.textValue(), fact.unit());
        }).toList();
        try {
            return objectMapper.writeValueAsString(new RedFlagMatchedInputSnapshot(facts));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize red-flag matched input snapshot", exception);
        }
    }

    private static SimpleModule localDateModule() {
        var module = new SimpleModule();
        module.addSerializer(LocalDate.class, new JsonSerializer<LocalDate>() {
            @Override
            public void serialize(LocalDate value, com.fasterxml.jackson.core.JsonGenerator generator,
                    SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        return module;
    }
}
