package com.project.iot_spring.route;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Random share tokens: 16 bytes from SecureRandom, base64url without padding = 22 characters. */
public final class ShareTokens {

    public static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{22}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isWellFormed(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }
}
