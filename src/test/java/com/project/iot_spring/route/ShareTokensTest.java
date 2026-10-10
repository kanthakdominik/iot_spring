package com.project.iot_spring.route;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShareTokensTest {

    @Test
    void tokensAre22UrlSafeCharacters() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = ShareTokens.generate();
            assertThat(token).hasSize(22).matches("^[A-Za-z0-9_-]+$");
            assertThat(ShareTokens.isWellFormed(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void rejectsMalformedTokens() {
        assertThat(ShareTokens.isWellFormed(null)).isFalse();
        assertThat(ShareTokens.isWellFormed("short")).isFalse();
        assertThat(ShareTokens.isWellFormed("AAAAAAAAAAAAAAAAAAAAA=")).isFalse();
        assertThat(ShareTokens.isWellFormed("AAAAAAAAAAAAAAAAAAAAAAA")).isFalse();
    }
}
