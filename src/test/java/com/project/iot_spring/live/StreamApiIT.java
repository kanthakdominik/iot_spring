package com.project.iot_spring.live;

import com.project.iot_spring.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/stream (SSE) - only the handshake, the stream itself is not consumed. */
class StreamApiIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void streamRequiresLogin() throws Exception {
        mvc.perform(get("/api/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loggedInUserGetsEventStream() throws Exception {
        mvc.perform(get("/api/stream").accept(MediaType.TEXT_EVENT_STREAM).with(user("viewer").roles("VIEWER")))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }
}
