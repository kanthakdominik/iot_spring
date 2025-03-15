package com.project.iot_spring.mqtt;

import com.project.iot_spring.web.service.ProcessService;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.stereotype.Service;
import java.util.logging.Logger;

@Service
public class MqttSubscriber {

    private static final Logger LOGGER = Logger.getLogger(MqttSubscriber.class.getName());

    private final MqttClient mqttClient;
    private final ProcessService processService;

    public MqttSubscriber(MqttClient mqttClient, ProcessService processService) throws MqttException {
        this.mqttClient = mqttClient;
        this.processService = processService;
        subscribe();
    }

    private void subscribe() throws MqttException {
        String topic = "esp32/data";
        mqttClient.subscribe(topic, (t, msg) -> {
            String payload = new String(msg.getPayload());
            LOGGER.info("Received message on topic [" + t + "]: " + payload);

            Thread.startVirtualThread(() -> processService.saveToDatabase(payload));
        });

        LOGGER.info("Subscribed to MQTT topic: " + topic);
    }
}
