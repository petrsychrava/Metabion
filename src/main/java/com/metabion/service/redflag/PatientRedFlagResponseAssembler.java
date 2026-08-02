package com.metabion.service.redflag;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.dto.redflag.PatientRedFlagEventResponse;
import com.metabion.dto.redflag.PatientRedFlagHistoryResponse;
import com.metabion.dto.redflag.PatientRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagWriteOutcomeResponse;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class PatientRedFlagResponseAssembler {

    public PatientRedFlagSnapshotResponse current(List<RedFlagEventReadModel> events) {
        var flags = events.stream().map(this::event).toList();
        return new PatientRedFlagSnapshotResponse(highestSeverity(flags), flags);
    }

    public PatientRedFlagHistoryResponse history(List<RedFlagEventReadModel> events, String nextCursor) {
        return new PatientRedFlagHistoryResponse(events.stream().map(this::event).toList(), nextCursor);
    }

    public RedFlagWriteOutcomeResponse outcome(RedFlagEvaluationOutcome outcome) {
        return new RedFlagWriteOutcomeResponse(
                outcome.highestSeverity(),
                outcome.currentFlags().stream()
                        .map(flag -> new PatientRedFlagEventResponse(
                                flag.eventId(),
                                flag.ruleKey(),
                                flag.severity(),
                                flag.detectedAt(),
                                flag.sourceType(),
                                flag.sourceId(),
                                true,
                                null))
                        .toList(),
                outcome.clearedRuleKeys());
    }

    private PatientRedFlagEventResponse event(RedFlagEventReadModel event) {
        return new PatientRedFlagEventResponse(
                event.eventId(),
                event.ruleKey(),
                event.severity(),
                event.detectedAt(),
                event.sourceType(),
                event.sourceId(),
                event.current(),
                event.supersededAt());
    }

    private static RedFlagSeverity highestSeverity(List<PatientRedFlagEventResponse> flags) {
        return flags.stream()
                .map(PatientRedFlagEventResponse::severity)
                .max(Comparator.comparingInt(RedFlagSeverity::priority))
                .orElse(null);
    }
}
