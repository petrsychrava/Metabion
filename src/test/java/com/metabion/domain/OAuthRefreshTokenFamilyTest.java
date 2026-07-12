package com.metabion.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class OAuthRefreshTokenFamilyTest {
    private static final Instant CREATED = Instant.parse("2026-07-06T10:00:00Z");

    @Test
    void validatesIdentityAndTracksOneWayRevocationLifecycle() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthRefreshTokenFamily(" ", CREATED));
        assertThatIllegalArgumentException().isThrownBy(() -> new OAuthRefreshTokenFamily("x".repeat(65), CREATED));
        assertThatNullPointerException().isThrownBy(() -> new OAuthRefreshTokenFamily("family-1", null));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OAuthRefreshTokenFamily("family-early", CREATED).revoke("reuse", CREATED.minusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new OAuthRefreshTokenFamily("family-reason", CREATED).revoke("x".repeat(121), CREATED));

        var family = new OAuthRefreshTokenFamily("family-1", CREATED);
        family.revoke("refresh_token_reuse", CREATED.plusSeconds(30));

        assertThat(family.getId()).isEqualTo("family-1");
        assertThat(family.getCreatedAt()).isEqualTo(CREATED);
        assertThat(family.isRevoked()).isTrue();
        assertThat(family.getRevokedAt()).isEqualTo(CREATED.plusSeconds(30));
        assertThat(family.getRevocationReason()).isEqualTo("refresh_token_reuse");
        assertThatIllegalArgumentException().isThrownBy(
                () -> family.revoke("again", CREATED.plusSeconds(31)));
    }
}
