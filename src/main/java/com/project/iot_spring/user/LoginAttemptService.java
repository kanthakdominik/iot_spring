package com.project.iot_spring.user;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple brute-force protection: after {@link #MAX_FAILURES} failed logins for the same
 * username + client address within {@link #WINDOW}, further attempts are rejected for {@link #LOCK_TIME}.
 * In-memory on purpose (single backend instance).
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK_TIME = Duration.ofMinutes(15);

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptService() {
        this(Clock.systemUTC());
    }

    LoginAttemptService(Clock clock) {
        this.clock = clock;
    }

    /** @throws LoginBlockedException when the key is currently locked */
    public void checkAllowed(String username, String clientAddress) {
        Attempts a = attempts.get(key(username, clientAddress));
        if (a != null && a.lockedUntil != null && clock.instant().isBefore(a.lockedUntil)) {
            throw new LoginBlockedException("Too many failed login attempts. Try again later.");
        }
    }

    public void loginFailed(String username, String clientAddress) {
        Instant now = clock.instant();
        attempts.compute(key(username, clientAddress), (k, a) -> {
            if (a == null || a.windowStart.plus(WINDOW).isBefore(now)) {
                a = new Attempts(now);
            }
            a.failures++;
            if (a.failures >= MAX_FAILURES) {
                a.lockedUntil = now.plus(LOCK_TIME);
            }
            return a;
        });
    }

    public void loginSucceeded(String username, String clientAddress) {
        attempts.remove(key(username, clientAddress));
    }

    private static String key(String username, String clientAddress) {
        return (username == null ? "" : username.toLowerCase(Locale.ROOT)) + "|" + clientAddress;
    }

    private static final class Attempts {
        final Instant windowStart;
        int failures;
        Instant lockedUntil;

        Attempts(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}
