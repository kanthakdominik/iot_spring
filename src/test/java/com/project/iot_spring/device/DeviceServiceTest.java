package com.project.iot_spring.device;

import com.project.iot_spring.event.EventService;
import com.project.iot_spring.event.EventTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTest {

    private final DeviceQueries deviceQueries = mock(DeviceQueries.class);
    private final EventService eventService = mock(EventService.class);
    private final DeviceCommandSender sender = mock(DeviceCommandSender.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DeviceCommandSender> senderProvider = mock(ObjectProvider.class);
    private final DeviceService service =
            new DeviceService(deviceQueries, mock(JdbcClient.class), eventService, senderProvider);

    @Test
    void sentCommandIsLogged() {
        when(deviceQueries.exists("esp32-1")).thenReturn(true);
        when(senderProvider.getIfAvailable()).thenReturn(sender);

        service.sendCommand("esp32-1", "new-route", "admin");

        verify(sender).sendCommand("esp32-1", "new-route");
        verify(eventService).log(eq("esp32-1"), isNull(), eq(EventTypes.COMMAND_SENT), eq(EventTypes.INFO),
                anyString(), eq(Map.of("cmd", "new-route", "user", "admin")));
    }

    @Test
    void unknownCommandIsRejectedAndNotLogged() {
        assertThatThrownBy(() -> service.sendCommand("esp32-1", "self-destruct", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(eventService, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedSendIsNotLogged() {
        when(deviceQueries.exists("esp32-1")).thenReturn(true);
        when(senderProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.sendCommand("esp32-1", "battery", "admin"))
                .isInstanceOf(MqttUnavailableException.class);
        verify(eventService, never()).log(any(), any(), any(), any(), any(), any());
    }
}
