package com.metabion.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("security-timing")
class LoginTimingIT extends AbstractAuthIT {

    @Test
    void unknown_vs_known_within_tolerance() {
        for (int i = 0; i < 8; i++) {
            createEnabledUser("known" + i + "@example.com", "CorrectPass123");
        }

        var unknownTimings = new ArrayList<Long>();
        var knownTimings = new ArrayList<Long>();
        for (int i = 0; i < 8; i++) {
            var index = i;
            unknownTimings.add(timed(() -> {
                var response = login(newClient(), "missing" + index + "@example.com", "WrongPass123");
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }));
            knownTimings.add(timed(() -> {
                var response = login(newClient(), "known" + index + "@example.com", "WrongPass123");
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            }));
        }

        var medianUnknown = median(unknownTimings);
        var medianKnown = median(knownTimings);
        var ratio = (double) medianKnown / medianUnknown;

        assertThat(ratio).isBetween(0.7, 1.3);
    }

    private static long timed(Runnable runnable) {
        var start = System.nanoTime();
        runnable.run();
        return System.nanoTime() - start;
    }

    private static long median(List<Long> timings) {
        Collections.sort(timings);
        return timings.get(timings.size() / 2);
    }
}
