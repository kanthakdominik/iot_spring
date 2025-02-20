package com.project.iot_spring.mqtt;

import com.project.iot_spring.database.DataRowService;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

@Service
public class MqttSubscriber {

    private static final Logger LOGGER = Logger.getLogger(MqttSubscriber.class.getName());

    private final MqttClient mqttClient;
    private final DataRowService dataRowService;

    public MqttSubscriber(MqttClient mqttClient, DataRowService dataRowService) throws MqttException {
        this.mqttClient = mqttClient;
        this.dataRowService = dataRowService;
        subscribe();
    }

    private void subscribe() throws MqttException {
        String topic = "esp32/data";
        mqttClient.subscribe(topic, (t, msg) -> {
            String payload = new String(msg.getPayload());
            LOGGER.info("Received message on topic [" + t + "]: " + payload);

            Thread.startVirtualThread(() -> dataRowService.saveToDatabase(payload));
        });

        LOGGER.info("✅ Subscribed to MQTT topic: " + topic);
    }
}
