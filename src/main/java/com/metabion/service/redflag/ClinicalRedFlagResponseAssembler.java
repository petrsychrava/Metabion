package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.dto.redflag.ClinicalRedFlagEventResponse;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.ClinicalRedFlagWriteOutcomeResponse;
import com.metabion.dto.redflag.RedFlagMatchedInputsResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class ClinicalRedFlagResponseAssembler {

    private final RedFlagSnapshotSerializer serializer;

    public ClinicalRedFlagResponseAssembler(RedFlagSnapshotSerializer serializer) {
        this.serializer = serializer;
    }

    public ClinicalRedFlagSnapshotResponse current(List<RedFlagEventReadModel> events) {
        var flags = events.stream().map(this::event).toList();
        return new ClinicalRedFlagSnapshotResponse(highestSeverity(flags), flags);
    }

    public ClinicalRedFlagHistoryResponse history(List<RedFlagEventReadModel> events, String nextCursor) {
        return new ClinicalRedFlagHistoryResponse(events.stream().map(this::event).toList(), nextCursor);
    }

    public ClinicalRedFlagWriteOutcomeResponse outcome(RedFlagEvaluationOutcome outcome) {
        return new ClinicalRedFlagWriteOutcomeResponse(
                outcome.highestSeverity(),
                outcome.currentFlags().stream()
                        .map(flag -> new ClinicalRedFlagEventResponse(
                                flag.eventId(),
                                flag.ruleKey(),
                                flag.severity(),
                                flag.detectedAt(),
                                flag.sourceType(),
                                flag.sourceId(),
                                true,
                                null,
                                flag.ruleVersion(),
                                matchedInputs(flag.matchedInputs())))
                        .toList(),
                outcome.clearedRuleKeys());
    }

    private ClinicalRedFlagEventResponse event(RedFlagEventReadModel event) {
        return new ClinicalRedFlagEventResponse(
                event.eventId(),
                event.ruleKey(),
                event.severity(),
                event.detectedAt(),
                event.sourceType(),
                event.sourceId(),
                event.current(),
                event.supersededAt(),
                event.ruleVersion(),
                matchedInputs(event.matchedInputs()));
    }

    private RedFlagMatchedInputsResponse matchedInputs(String snapshot) {
        var matchedInputs = serializer.deserialize(snapshot);
        return new RedFlagMatchedInputsResponse(matchedInputs.facts().stream()
                .map(fact -> new RedFlagMatchedInputsResponse.Fact(
                        fact.sourceType(),
                        fact.sourceId(),
                        fact.factKey(),
                        fact.observedOn(),
                        fact.decimalValue(),
                        fact.textValue(),
                        fact.unit()))
                .toList());
    }

    private static RedFlagSeverity highestSeverity(List<ClinicalRedFlagEventResponse> flags) {
        return flags.stream()
                .map(ClinicalRedFlagEventResponse::severity)
                .max(Comparator.comparingInt(RedFlagSeverity::priority))
                .orElse(null);
    }
}
