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

    @Test
    void rejectsNullChallengeAndVerifier() {
        var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        var challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

        assertThat(service.matches("S256", null, verifier)).isFalse();
        assertThat(service.matches("S256", challenge, null)).isFalse();
    }

    @Test
    void rejectsChangedVerifierForValidChallenge() {
        var challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
        var changedVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXl";

        assertThat(service.matches("S256", challenge, changedVerifier)).isFalse();
    }

    @Test
    void rejectsVerifierShorterThanRfcMinimum() {
        var verifier = "abc";
        var challenge = "ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0";

        assertThat(service.matches("S256", challenge, verifier)).isFalse();
    }

    @Test
    void rejectsVerifierWithInvalidCharacter() {
        var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjX!";
        var challenge = "Vrp1QH68e1honMA83I_xZh-xXj8gQLw6Ll9vjAbRsVk";

        assertThat(service.matches("S256", challenge, verifier)).isFalse();
    }
}
