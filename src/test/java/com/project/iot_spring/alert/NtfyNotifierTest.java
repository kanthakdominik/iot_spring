package com.project.iot_spring.alert;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class NtfyNotifierTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsMessageWithNtfyHeaders() throws Exception {
        Map<String, String> received = new ConcurrentHashMap<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/radmon-test", exchange -> {
            received.put("method", exchange.getRequestMethod());
            received.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getRequestHeaders().forEach((name, values) -> received.put(name.toLowerCase(), values.getFirst()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/radmon-test";

        NtfyNotifier notifier = new NtfyNotifier(url, "tk_secret", "https://iot.example.org/");
        int status = notifier.post("Wysoki poziom promieniowania", "Dose rate 0.620 µSv/h", true);

        assertThat(status).isEqualTo(200);
        assertThat(received.get("method")).isEqualTo("POST");
        assertThat(received.get("body")).isEqualTo("Dose rate 0.620 µSv/h");
        assertThat(received.get("title")).isEqualTo("Wysoki poziom promieniowania");
        assertThat(received.get("priority")).isEqualTo("high");
        assertThat(received.get("tags")).isEqualTo("warning");
        assertThat(received.get("click")).isEqualTo("https://iot.example.org/alerts");
        assertThat(received.get("authorization")).isEqualTo("Bearer tk_secret");
    }

    @Test
    void disabledWithoutUrl() {
        NtfyNotifier notifier = new NtfyNotifier("", "", "");
        assertThat(notifier.isEnabled()).isFalse();
        notifier.send("title", "message", false);   // no-op, no exception
    }

    @Test
    void nonAsciiTitleIsEncoded() {
        assertThat(NtfyNotifier.encodeHeader("Niska bateria")).isEqualTo("Niska bateria");
        assertThat(NtfyNotifier.encodeHeader("Urządzenie offline"))
                .isEqualTo("=?UTF-8?B?VXJ6xIVkemVuaWUgb2ZmbGluZQ==?=");
    }
}
