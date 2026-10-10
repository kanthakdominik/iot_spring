package com.project.iot_spring.user;

import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Own account of any logged-in user: preferences and password (VIEWER is enough). */
class AccountApiIT extends IntegrationTest {

    private static final String VIEWER = "viewer.1";
    private static final String VIEWER_PASSWORD = "viewer-password-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void createViewer() {
        userRepository.save(new User(VIEWER, passwordEncoder.encode(VIEWER_PASSWORD), User.ROLE_VIEWER));
    }

    @Test
    void viewerCanSavePreferences() throws Exception {
        mvc.perform(put("/api/auth/preferences").with(csrf()).with(user(VIEWER).roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\",\"language\":\"en\",\"doseUnit\":\"nSv\",\"mapLayer\":\"topo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(VIEWER))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.preferences.mapLayer").value("topo"));

        // stored on the server: visible after a new login
        MockHttpSession session = (MockHttpSession) login(VIEWER, VIEWER_PASSWORD)
                .andExpect(jsonPath("$.preferences.theme").value("dark"))
                .andExpect(jsonPath("$.preferences.language").value("en"))
                .andExpect(jsonPath("$.preferences.doseUnit").value("nSv"))
                .andReturn().getRequest().getSession(false);
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.preferences.mapLayer").value("topo"));
    }

    @Test
    void missingPreferencesGetDefaultsAndInvalidOnesAreRejected() throws Exception {
        mvc.perform(put("/api/auth/preferences").with(csrf()).with(user(VIEWER).roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"light\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences.theme").value("light"))
                .andExpect(jsonPath("$.preferences.language").value("pl"))
                .andExpect(jsonPath("$.preferences.doseUnit").value("uSv"))
                .andExpect(jsonPath("$.preferences.mapLayer").value("osm"));

        mvc.perform(put("/api/auth/preferences").with(csrf()).with(user(VIEWER).roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"neon\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void preferencesNeedLogin() throws Exception {
        mvc.perform(put("/api/auth/preferences").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanChangeOwnPassword() throws Exception {
        mvc.perform(post("/api/auth/password").with(csrf()).with(user(VIEWER).roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + VIEWER_PASSWORD + "\",\"newPassword\":\"new-password-123\"}"))
                .andExpect(status().isNoContent());

        login(VIEWER, "new-password-123").andExpect(status().isOk());
        login(VIEWER, VIEWER_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongCurrentPasswordGives400() throws Exception {
        mvc.perform(post("/api/auth/password").with(csrf()).with(user(VIEWER).roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong-password\",\"newPassword\":\"new-password-123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooShortNewPasswordGives400() throws Exception {
        mvc.perform(post("/api/auth/password").with(csrf()).with(user(VIEWER).roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + VIEWER_PASSWORD + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerCanLogOut() throws Exception {
        MockHttpSession session = (MockHttpSession) login(VIEWER, VIEWER_PASSWORD)
                .andReturn().getRequest().getSession(false);

        mvc.perform(post("/api/auth/logout").with(csrf()).session(session)).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }
}
