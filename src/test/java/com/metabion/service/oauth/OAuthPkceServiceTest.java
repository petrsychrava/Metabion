package com.metabion.service.oauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthPkceServiceTest {

    private final OAuthPkceService service = new OAuthPkceService();

    @Test
    void s256VerifierMatchesChallenge() {
        var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        assertThat(service.matches("S256", challenge, verifier)).isTrue();
    }

    @Test
    void rejectsPlainMethodAndWrongVerifier() {
        assertThat(service.matches("plain", "abc", "abc")).isFalse();
        assertThat(service.matches("S256", "wrong", "abc")).isFalse();
    }
}
