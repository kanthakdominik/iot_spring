package com.project.iot_spring.live;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server-Sent Events hub for {@code GET /api/stream}.
 * <ul>
 *   <li>at most {@link #MAX_CLIENTS} connections, each closed after {@link #TIMEOUT_MS}
 *       (EventSource reconnects by itself)</li>
 *   <li>{@link LiveEvent}s are delivered after the transaction that produced them commits; the listener only
 *       puts the serialized event into a bounded per-client queue, a virtual thread per client writes it -
 *       a slow client never blocks ingest or the other clients</li>
 *   <li>a client whose queue overflows or whose connection fails is dropped (it reconnects and fetches
 *       missed samples with {@code ?after=})</li>
 *   <li>a {@code : ping} comment every 20 s keeps proxies from closing idle connections</li>
 * </ul>
 */
@Slf4j
@Component
public class LiveEventBroadcaster {

    static final int MAX_CLIENTS = 50;
    static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(30);
    static final int QUEUE_CAPACITY = 1000;

    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final JsonMapper jsonMapper;

    public LiveEventBroadcaster(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public SseEmitter subscribe(String username) {
        if (clients.size() >= MAX_CLIENTS) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Too many live connections");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Client client = new Client(emitter, username);
        emitter.onCompletion(() -> remove(client));
        emitter.onTimeout(() -> {
            remove(client);
            emitter.complete();
        });
        emitter.onError(e -> remove(client));
        clients.add(client);
        client.start();
        log.debug("SSE client connected: {} ({} connections)", username, clients.size());
        return emitter;
    }

    public int clientCount() {
        return clients.size();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLiveEvent(LiveEvent event) {
        if (clients.isEmpty()) {
            return;
        }
        String json;
        try {
            json = jsonMapper.writeValueAsString(event.data());
        } catch (JacksonException e) {
            log.error("Cannot serialize live event '{}'", event.name(), e);
            return;
        }
        Outgoing message = new Outgoing(event.name(), json);
        for (Client client : clients) {
            client.offer(message);
        }
    }

    @Scheduled(fixedRate = 20, timeUnit = TimeUnit.SECONDS)
    public void ping() {
        for (Client client : clients) {
            client.offer(Outgoing.PING);
        }
    }

    @PreDestroy
    public void shutdown() {
        for (Client client : List.copyOf(clients)) {
            client.close();
        }
    }

    private void remove(Client client) {
        if (clients.remove(client)) {
            client.stopThread();
            log.debug("SSE client disconnected: {} ({} connections)", client.username, clients.size());
        }
    }

    /** One serialized message; {@code name == null} means a ping comment. */
    private record Outgoing(String name, String json) {
        static final Outgoing PING = new Outgoing(null, null);
    }

    private final class Client {

        private final SseEmitter emitter;
        private final String username;
        private final BlockingQueue<Outgoing> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        private volatile Thread thread;

        Client(SseEmitter emitter, String username) {
            this.emitter = emitter;
            this.username = username;
        }

        void start() {
            thread = Thread.ofVirtual().name("sse-" + username).start(this::run);
        }

        void offer(Outgoing message) {
            if (!queue.offer(message)) {
                log.warn("SSE client {} is too slow - closing the connection", username);
                close();
            }
        }

        void close() {
            remove(this);
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                log.debug("Error while closing SSE emitter", e);
            }
        }

        void stopThread() {
            Thread t = thread;
            if (t != null && t != Thread.currentThread()) {
                t.interrupt();
            }
        }

        private void run() {
            try {
                while (clients.contains(this)) {
                    Outgoing message = queue.take();
                    if (message.name() == null) {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } else {
                        emitter.send(SseEmitter.event().name(message.name()).data(message.json()));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                // client went away; the servlet container completes the emitter
                log.debug("SSE send to {} failed: {}", username, e.getMessage());
                remove(this);
            }
        }
    }
}
