package com.project.iot_spring.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_VIEWER = "VIEWER";
    public static final Set<String> ROLES = Set.of(ROLE_ADMIN, ROLE_VIEWER);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt hash. */
    @Column(nullable = false, length = 100)
    private String password;

    /** Role name without the ROLE_ prefix: ADMIN or VIEWER. */
    @Column(nullable = false, length = 20)
    private String role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // "preferences" (JSONB) is read and written with plain SQL by UserService

    public User(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
