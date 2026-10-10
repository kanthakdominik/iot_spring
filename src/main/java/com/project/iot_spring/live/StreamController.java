package com.project.iot_spring.live;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live updates for the browser (EventSource), docs/V2.1-SPEC.md section 3.6. */
@RestController
@RequiredArgsConstructor
public class StreamController {

    private final LiveEventBroadcaster broadcaster;

    @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication authentication) {
        return broadcaster.subscribe(authentication.getName());
    }
}
