package com.project.iot_spring.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnBean(MqttClient.class)
public class MqttPublisher {

    private final MqttClient mqttClient;

    public MqttPublisher(MqttClient mqttClient) {
        this.mqttClient = mqttClient;
    }

    public void publish(String topic, String message) throws MqttException {
        MqttMessage mqttMessage = new MqttMessage(message.getBytes());
        mqttMessage.setQos(1);
        mqttClient.publish(topic, mqttMessage);
        System.out.println("📤 Published MQTT message to topic [" + topic + "]: " + message);
    }
}