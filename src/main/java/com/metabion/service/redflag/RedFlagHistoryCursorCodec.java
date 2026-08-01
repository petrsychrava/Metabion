package com.metabion.service.redflag;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

@Component
public class RedFlagHistoryCursorCodec {

    public String encode(Instant triggeredAt, Long eventId) {
        Objects.requireNonNull(triggeredAt);
        Objects.requireNonNull(eventId);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((triggeredAt + "|" + eventId).getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Cursor> decode(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        try {
            var decoded = new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var separator = decoded.lastIndexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw invalidCursor();
            }
            var triggeredAt = Instant.parse(decoded.substring(0, separator));
            var eventId = Long.parseLong(decoded.substring(separator + 1));
            if (eventId <= 0) {
                throw invalidCursor();
            }
            return Optional.of(new Cursor(triggeredAt, eventId));
        } catch (RuntimeException ex) {
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            throw invalidCursor();
        }
    }

    public record Cursor(Instant triggeredAt, Long eventId) {
    }

    private static ResponseStatusException invalidCursor() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
    }
}
