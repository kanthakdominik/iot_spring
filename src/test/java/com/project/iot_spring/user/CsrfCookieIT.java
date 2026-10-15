package com.project.iot_spring.user;

import com.project.iot_spring.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Angular app reads the CSRF token from the XSRF-TOKEN cookie. Checked over real HTTP on a running server:
 * MockMvc with Spring Security test support replaces the CSRF token repository, so it cannot show this.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.admin.password=test-password",
        "mqtt.enabled=false",
        "app.stats.job.enabled=false"
})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CsrfCookieIT {

    @Value("${local.server.port}")
    private int port;

    @Test
    void firstCallOfTheSpaGetsReadableXsrfCookie() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues("set-cookie"))
                .anySatisfy(cookie -> {
                    assertThat(cookie).startsWith("XSRF-TOKEN=");
                    assertThat(cookie).doesNotContainIgnoringCase("HttpOnly");
                });
    }
}
