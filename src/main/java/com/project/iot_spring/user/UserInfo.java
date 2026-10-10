package com.project.iot_spring.user;

/** The logged-in user ({@code /api/auth/login}, {@code /api/auth/me}). */
public record UserInfo(String username, String role, Preferences preferences) {
}
