package com.metabion.dto.redflag;

import com.metabion.domain.RedFlagSeverity;

import java.time.LocalDate;

public record RedFlagHistoryQuery(
        LocalDate from,
        LocalDate to,
        RedFlagSeverity severity,
        String cursor,
        Integer size) {
}
