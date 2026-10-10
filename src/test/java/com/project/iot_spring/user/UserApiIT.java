package com.project.iot_spring.user;

import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** User management (ADMIN): /api/users. */
class UserApiIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void adminCreatesListsAndDeletesUsers() throws Exception {
        mvc.perform(post("/api/users").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jan.k\",\"password\":\"secret-password\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.username").value("jan.k"))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.createdAt", notNullValue()));

        mvc.perform(get("/api/users").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", contains(ADMIN_USER, "jan.k")));

        // the new account can log in
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jan.k\",\"password\":\"secret-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));

        long id = userRepository.findByUsername("jan.k").orElseThrow().getId();
        mvc.perform(delete("/api/users/{id}", id).with(csrf()).with(admin()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/users").with(admin()))
                .andExpect(jsonPath("$[*].username", contains(ADMIN_USER)));
    }

    @Test
    void duplicateUsernameGives409AndInvalidInputGives400() throws Exception {
        mvc.perform(post("/api/users").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Admin\",\"password\":\"secret-password\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/users").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"password\":\"secret-password\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ola\",\"password\":\"short\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users").with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ola\",\"password\":\"secret-password\",\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerCannotManageUsers() throws Exception {
        mvc.perform(get("/api/users").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/users").with(csrf()).with(user("viewer").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ola\",\"password\":\"secret-password\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void roleChangeAndPasswordReset() throws Exception {
        long id = createUser("ola", User.ROLE_VIEWER);

        mvc.perform(patch("/api/users/{id}", id).with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"password\":\"another-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ola\",\"password\":\"another-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void lastAdministratorCannotBeDemotedOrDeleted() throws Exception {
        long adminId = userRepository.findByUsername(ADMIN_USER).orElseThrow().getId();

        mvc.perform(patch("/api/users/{id}", adminId).with(csrf()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());
        // another (e.g. technical) admin account tries to delete the only ADMIN in the database
        mvc.perform(delete("/api/users/{id}", adminId).with(csrf()).with(user("root").roles("ADMIN")))
                .andExpect(status().isConflict());

        // with a second admin the first one can be demoted
        createUser("second-admin", User.ROLE_ADMIN);
        mvc.perform(patch("/api/users/{id}", adminId).with(csrf()).with(user("second-admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("VIEWER"));
    }

    @Test
    void nobodyCanDeleteOwnAccount() throws Exception {
        long adminId = userRepository.findByUsername(ADMIN_USER).orElseThrow().getId();
        createUser("second-admin", User.ROLE_ADMIN);

        mvc.perform(delete("/api/users/{id}", adminId).with(csrf()).with(admin()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/users/{id}", 999_999).with(csrf()).with(admin()))
                .andExpect(status().isNotFound());
    }

    private long createUser(String username, String role) {
        return userRepository.save(new User(username, passwordEncoder.encode("initial-password"), role)).getId();
    }

    private static RequestPostProcessor admin() {
        return user(ADMIN_USER).roles("ADMIN");
    }
}
