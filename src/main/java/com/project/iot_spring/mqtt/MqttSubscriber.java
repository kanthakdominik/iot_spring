package com.project.iot_spring.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MqttSubscriber {

//    @Value("${mqt2

    private final MqttClient mqttClient;

    public MqttSubscriber(MqttClient mqttClient) throws MqttException {
        this.mqttClient = mqttClient;
        subscribe();
    }

    private void subscribe() throws MqttException {
        String topic = "esp32/data";
        mqttClient.subscribe(topic, (t, msg) -> {
            String payload = new String(msg.getPayload());
            System.out.println("📩 Received MQTT message on topic [" + t + "]: " + payload);
        });

        System.out.println("✅ Subscribed to MQTT topic: " + topic);
    }
}
