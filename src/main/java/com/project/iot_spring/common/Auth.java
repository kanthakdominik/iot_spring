package com.project.iot_spring.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public final class Auth {

    private Auth() {
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}
