package com.metabion.service.redflag;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedFlagHistoryCursorCodecTest {

    private final RedFlagHistoryCursorCodec codec = new RedFlagHistoryCursorCodec();

    @Test
    void roundTripsTimestampAndEventId() {
        var at = Instant.parse("2026-08-01T10:15:30.123456Z");
        var encoded = codec.encode(at, 701L);

        assertThat(codec.decode(encoded))
                .contains(new RedFlagHistoryCursorCodec.Cursor(at, 701L));
    }

    @Test
    void rejectsMalformedCursorWithoutEchoingIt() {
        assertThatThrownBy(() -> codec.decode("patient-value"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(error.getReason()).isEqualTo("invalid cursor");
                });
    }
}
