package com.project.iot_spring.user;

import java.time.Instant;

public record UserDto(Long id, String username, String role, Instant createdAt) {

    static UserDto from(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getRole(), u.getCreatedAt());
    }
}
