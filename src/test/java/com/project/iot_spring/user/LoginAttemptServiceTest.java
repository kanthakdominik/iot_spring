package com.project.iot_spring.user;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptServiceTest {

    /** A clock that the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T20:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service = new LoginAttemptService(clock);

    @Test
    void blocksAfterTooManyFailures() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.loginFailed("admin", "1.2.3.4");
        }

        assertThatThrownBy(() -> service.checkAllowed("admin", "1.2.3.4"))
                .isInstanceOf(LoginBlockedException.class);
        // another client address is not affected
        assertThatCode(() -> service.checkAllowed("admin", "5.6.7.8")).doesNotThrowAnyException();
    }

    @Test
    void unlocksAfterLockTime() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.loginFailed("admin", "1.2.3.4");
        }
        clock.advance(LoginAttemptService.LOCK_TIME.plusSeconds(1));

        assertThatCode(() -> service.checkAllowed("admin", "1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void successResetsCounter() {
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.loginFailed("admin", "1.2.3.4");
        }
        service.loginSucceeded("admin", "1.2.3.4");
        service.loginFailed("admin", "1.2.3.4");

        assertThatCode(() -> service.checkAllowed("admin", "1.2.3.4")).doesNotThrowAnyException();
    }
}
