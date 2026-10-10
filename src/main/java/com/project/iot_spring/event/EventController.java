package com.project.iot_spring.event;

import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /** Newest first; optional filters {@code deviceId}, {@code type} and {@code routeId}. */
    @GetMapping
    public PageResponse<EventDto> find(@RequestParam(required = false) String deviceId,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) Long routeId,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return eventService.find(deviceId, type, routeId, Paging.of(page, size));
    }
}
