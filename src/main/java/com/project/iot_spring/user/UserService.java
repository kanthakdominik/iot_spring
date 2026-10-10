package com.project.iot_spring.user;

import com.project.iot_spring.web.ConflictException;
import com.project.iot_spring.web.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * User accounts: own preferences and password (any logged-in user), user management (ADMIN).
 * Rules: there is always at least one ADMIN, nobody can delete their own account.
 */
@Service
public class UserService {

    public static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,50}$");
    /** BCrypt uses only the first 72 bytes - Spring Security rejects longer passwords. */
    static final int BCRYPT_MAX_BYTES = 72;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, JdbcClient jdbc,
                       JsonMapper jsonMapper) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    // ------------------------------------------------------------------ own account

    @Transactional(readOnly = true)
    public Optional<UserInfo> userInfo(String username) {
        return jdbc.sql("SELECT username, role, preferences::text AS preferences FROM users WHERE username = :u")
                .param("u", username)
                .query((rs, n) -> new UserInfo(rs.getString("username"), rs.getString("role"),
                        parsePreferences(rs.getString("preferences"))))
                .optional();
    }

    @Transactional
    public UserInfo updatePreferences(String username, Preferences preferences) {
        Preferences valid = (preferences == null ? Preferences.DEFAULTS : preferences).validated();
        int updated = jdbc.sql("UPDATE users SET preferences = CAST(:p AS jsonb) WHERE username = :u")
                .param("p", jsonMapper.writeValueAsString(valid))
                .param("u", username)
                .update();
        if (updated == 0) {
            throw new NotFoundException("User not found: " + username);
        }
        return userInfo(username).orElseThrow(() -> new NotFoundException("User not found: " + username));
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User not found: " + username));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        user.setPassword(encode(newPassword));
    }

    // ------------------------------------------------------------------ user management (ADMIN)

    @Transactional(readOnly = true)
    public List<UserDto> findAll() {
        return userRepository.findAll().stream()
                .sorted((a, b) -> a.getUsername().compareToIgnoreCase(b.getUsername()))
                .map(UserDto::from)
                .toList();
    }

    @Transactional
    public UserDto create(String username, String password, String role) {
        String name = username == null ? "" : username.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Username must match " + USERNAME.pattern());
        }
        checkRole(role);
        if (userRepository.existsByUsernameIgnoreCase(name)) {
            throw new ConflictException("Username is already taken: " + name);
        }
        return UserDto.from(userRepository.saveAndFlush(new User(name, encode(password), role)));
    }

    @Transactional
    public UserDto update(long id, String role, String password) {
        User user = get(id);
        if (role != null) {
            checkRole(role);
            if (User.ROLE_ADMIN.equals(user.getRole()) && !User.ROLE_ADMIN.equals(role) && adminCount() <= 1) {
                throw new ConflictException("Cannot remove the ADMIN role from the last administrator");
            }
            user.setRole(role);
        }
        if (password != null) {
            user.setPassword(encode(password));
        }
        return UserDto.from(user);
    }

    @Transactional
    public void delete(long id, String currentUsername) {
        User user = get(id);
        if (user.getUsername().equals(currentUsername)) {
            throw new ConflictException("You cannot delete your own account");
        }
        if (User.ROLE_ADMIN.equals(user.getRole()) && adminCount() <= 1) {
            throw new ConflictException("Cannot delete the last administrator");
        }
        userRepository.delete(user);
    }

    private long adminCount() {
        return userRepository.countByRole(User.ROLE_ADMIN);
    }

    private User get(long id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found: " + id));
    }

    private String encode(String password) {
        if (password == null || password.length() < 10 || password.length() > 100) {
            throw new IllegalArgumentException("Password must have 10-100 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new IllegalArgumentException("Password is too long (max " + BCRYPT_MAX_BYTES + " bytes)");
        }
        return passwordEncoder.encode(password);
    }

    private static void checkRole(String role) {
        if (role == null || !User.ROLES.contains(role)) {
            throw new IllegalArgumentException("Invalid role: " + role + ". Allowed: " + User.ROLES);
        }
    }

    private Preferences parsePreferences(String json) {
        if (json == null || json.isBlank()) {
            return Preferences.DEFAULTS;
        }
        try {
            return Preferences.fromStored(jsonMapper.readValue(json, MAP_TYPE));
        } catch (JacksonException e) {
            return Preferences.DEFAULTS;
        }
    }
}
