package com.project.iot_spring.mqtt;

import com.project.iot_spring.device.DeviceCommandSender;
import com.project.iot_spring.device.MqttUnavailableException;
import com.project.iot_spring.ingest.MqttMessageDispatcher;
import com.project.iot_spring.ingest.MqttTopic;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Connection to the MQTT broker (Mosquitto, TLS with client certificate).
 * <ul>
 *   <li>the application starts even when the broker is down - the first connection is retried every 10 s,
 *       later reconnects are handled by Paho (automatic reconnect)</li>
 *   <li>subscriptions are (re)created after every successful connection</li>
 *   <li>messages are processed one by one on the Paho callback thread, which keeps per-device order</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true")
public class MqttGateway implements MqttCallbackExtended, SmartLifecycle, DeviceCommandSender {

    private static final int[] SUBSCRIPTION_QOS = {0, 1, 0};
    private static final long RETRY_SECONDS = 10;

    private final MqttMessageDispatcher dispatcher;
    private final JsonMapper jsonMapper;
    private final String brokerUrl;
    private final String clientId;
    private final String certDir;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-connector");
        t.setDaemon(true);
        return t;
    });

    private volatile MqttClient client;
    private volatile boolean running;

    public MqttGateway(MqttMessageDispatcher dispatcher,
                       JsonMapper jsonMapper,
                       @Value("${mqtt.broker-url}") String brokerUrl,
                       @Value("${mqtt.client-id:iot_spring}") String clientId,
                       @Value("${mqtt.cert-dir:}") String certDir) {
        this.dispatcher = dispatcher;
        this.jsonMapper = jsonMapper;
        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
        this.certDir = certDir;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        running = true;
        executor.execute(this::connect);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        MqttClient c = client;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.disconnect(2_000);
                }
                c.close();
            } catch (MqttException e) {
                log.debug("Error while closing MQTT client", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    private void connect() {
        if (!running) {
            return;
        }
        try {
            if (client == null) {
                client = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                client.setCallback(this);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setSocketFactory(MqttSslContextFactory.create(certDir).getSocketFactory());
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setMaxReconnectDelay(30_000);
            options.setKeepAliveInterval(60);
            options.setConnectionTimeout(20);
            client.connect(options);
            // subscriptions are made in connectComplete()
        } catch (Exception e) {
            log.warn("MQTT connection to {} failed ({}), retrying in {} s", brokerUrl, e.getMessage(), RETRY_SECONDS);
            if (running) {
                executor.schedule(this::connect, RETRY_SECONDS, TimeUnit.SECONDS);
            }
        }
    }

    // ------------------------------------------------------------------ Paho callbacks

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT {} to {}", reconnect ? "reconnected" : "connected", serverURI);
        // do not block the Paho callback thread
        executor.execute(this::subscribe);
    }

    private void subscribe() {
        try {
            client.subscribe(MqttTopic.BACKEND_SUBSCRIPTIONS, SUBSCRIPTION_QOS);
            log.info("Subscribed to MQTT topics: {}", Arrays.toString(MqttTopic.BACKEND_SUBSCRIPTIONS));
        } catch (MqttException e) {
            log.error("MQTT subscribe failed", e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {} (automatic reconnect is on)", cause == null ? "?" : cause.getMessage());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // must never throw - Paho would close the connection
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            dispatcher.dispatch(topic, payload, Instant.now());
        } catch (RuntimeException e) {
            log.error("Unexpected error while handling MQTT message on [{}]", topic, e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // nothing to do
    }

    // ------------------------------------------------------------------ commands

    @Override
    public void sendCommand(String deviceId, String command) {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            throw new MqttUnavailableException("Not connected to the MQTT broker");
        }
        try {
            byte[] payload = jsonMapper.writeValueAsBytes(Map.of("cmd", command));
            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            message.setRetained(false);
            c.publish(MqttTopic.commandTopic(deviceId), message);
            log.info("Sent command '{}' to device {}", command, deviceId);
        } catch (MqttException e) {
            throw new MqttUnavailableException("Failed to publish command: " + e.getMessage());
        }
    }
}
