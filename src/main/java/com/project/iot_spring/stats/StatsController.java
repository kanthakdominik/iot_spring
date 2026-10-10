package com.project.iot_spring.stats;

import com.project.iot_spring.common.Auth;
import com.project.iot_spring.stats.StatsDtos.ActivityDay;
import com.project.iot_spring.stats.StatsDtos.Grid;
import com.project.iot_spring.stats.StatsDtos.SearchResult;
import com.project.iot_spring.stats.StatsDtos.Summary;
import com.project.iot_spring.web.TimeParams;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/api/stats/summary")
    public Summary summary(Authentication authentication) {
        return statsService.summary(Auth.isAdmin(authentication));
    }

    /** Days with data in [from, to) - default the last 365 days. */
    @GetMapping("/api/stats/activity")
    public List<ActivityDay> activity(@RequestParam(required = false) String from,
                                      @RequestParam(required = false) String to) {
        return statsService.activity(TimeParams.lowerBound(from, "from", statsService.zone()),
                TimeParams.upperBound(to, "to", statsService.zone()));
    }

    @GetMapping("/api/map/grid")
    public Grid grid(@RequestParam double south, @RequestParam double west,
                     @RequestParam double north, @RequestParam double east,
                     @RequestParam int zoom,
                     @RequestParam(required = false) String from,
                     @RequestParam(required = false) String to,
                     @RequestParam(required = false) String deviceId) {
        return statsService.grid(south, west, north, east, zoom,
                TimeParams.lowerBound(from, "from", statsService.zone()),
                TimeParams.upperBound(to, "to", statsService.zone()), deviceId);
    }

    @GetMapping("/api/search")
    public SearchResult search(@RequestParam(required = false) String q) {
        return statsService.search(q);
    }
}
