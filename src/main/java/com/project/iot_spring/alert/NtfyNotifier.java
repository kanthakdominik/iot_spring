package com.project.iot_spring.alert;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Push notifications through <a href="https://ntfy.sh">ntfy</a>: {@code POST app.notify.ntfy.url} with the
 * message as body. Disabled when the URL is empty. Sending is asynchronous (virtual threads) and never throws.
 */
@Slf4j
@Component
public class NtfyNotifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String url;
    private final String token;
    private final String publicUrl;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public NtfyNotifier(@Value("${app.notify.ntfy.url:}") String url,
                        @Value("${app.notify.ntfy.token:}") String token,
                        @Value("${app.public-url:}") String publicUrl) {
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token.trim();
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        if (!this.url.isEmpty()) {
            log.info("Push notifications enabled (ntfy)");
        }
    }

    public boolean isEnabled() {
        return !url.isEmpty();
    }

    /** Queues a notification; returns immediately. */
    public void send(String title, String message, boolean highPriority) {
        if (!isEnabled()) {
            return;
        }
        executor.execute(() -> {
            try {
                post(title, message, highPriority);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("ntfy notification failed: {}", e.toString());
            }
        });
    }

    /** Sends synchronously (used by {@link #send} and by tests). */
    int post(String title, String message, boolean highPriority) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Title", encodeHeader(title))
                .header("Priority", highPriority ? "high" : "default")
                .header("Tags", "warning")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8));
        if (!publicUrl.isEmpty()) {
            request.header("Click", publicUrl + "/alerts");
        }
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) {
            log.warn("ntfy answered HTTP {}", response.statusCode());
        }
        return response.statusCode();
    }

    /** HTTP headers must be ASCII - other text is sent as an RFC 2047 encoded word (ntfy decodes it). */
    static String encodeHeader(String value) {
        if (value == null) {
            return "";
        }
        boolean ascii = value.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
        if (ascii) {
            return value;
        }
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
