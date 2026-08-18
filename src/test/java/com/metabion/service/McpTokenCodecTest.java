package com.metabion.service;

import com.metabion.domain.McpTokenSubject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpTokenCodecTest {

    private final McpTokenCodec codec = new McpTokenCodec();

    @Test
    void generatesClinicianTokensWithTheClinicianPrefixAndExpectedBodyLength() {
        var token = codec.generate(McpTokenSubject.CLINICIAN);

        assertThat(token).startsWith("clin_");
        assertThat(token.substring("clin_".length())).hasSize(43);
    }

    @Test
    void routesLegacyAndPrefixedPatientTokens() {
        var body = "abcdefghijklmnopqrstuvwxyzABCDEFGHijklmnopq";

        assertThat(codec.route("pat_" + body)).isEqualTo(McpTokenCodec.Route.PATIENT);
        assertThat(codec.route(body)).isEqualTo(McpTokenCodec.Route.LEGACY_PATIENT);
        assertThat(codec.route("clin_" + body)).isEqualTo(McpTokenCodec.Route.CLINICIAN);
        assertThat(codec.route("pat_short")).isEqualTo(McpTokenCodec.Route.INVALID);
        assertThat(codec.route("bogus_" + body)).isEqualTo(McpTokenCodec.Route.INVALID);
    }

    @Test
    void sha256HexKeepsTheCompatibilityRepresentation() {
        assertThat(McpTokenCodec.sha256Hex("plain")).hasSize(64);
    }
}
