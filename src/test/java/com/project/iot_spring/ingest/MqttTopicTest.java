package com.project.iot_spring.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqttTopicTest {

    @Test
    void parsesDeviceTopics() {
        assertThat(MqttTopic.parse("radmon/v1/esp32-1/telemetry"))
                .contains(new MqttTopic("esp32-1", MqttTopic.Kind.TELEMETRY));
        assertThat(MqttTopic.parse("radmon/v1/esp32-2/status"))
                .contains(new MqttTopic("esp32-2", MqttTopic.Kind.STATUS));
        assertThat(MqttTopic.parse("radmon/v1/esp32-2/event"))
                .contains(new MqttTopic("esp32-2", MqttTopic.Kind.EVENT));
    }

    @Test
    void rejectsUnknownOrInvalidTopics() {
        assertThat(MqttTopic.parse("esp32/data")).isEmpty();
        assertThat(MqttTopic.parse("radmon/v2/esp32-1/telemetry")).isEmpty();
        assertThat(MqttTopic.parse("radmon/v1/ESP32_1/telemetry")).isEmpty();
        assertThat(MqttTopic.parse("radmon/v1/esp32-1/telemetry/extra")).isEmpty();
        assertThat(MqttTopic.parse(null)).isEmpty();
    }

    @Test
    void buildsCommandTopic() {
        assertThat(MqttTopic.commandTopic("esp32-1")).isEqualTo("radmon/v1/esp32-1/cmd");
    }
}
