package com.project.iot_spring.route;

import com.project.iot_spring.common.Auth;
import com.project.iot_spring.route.RouteDtos.MarkerCreateRequest;
import com.project.iot_spring.route.RouteDtos.MarkerDto;
import com.project.iot_spring.route.RouteDtos.MeasurementDto;
import com.project.iot_spring.route.RouteDtos.RouteDto;
import com.project.iot_spring.route.RouteDtos.RouteUpdateRequest;
import com.project.iot_spring.route.RouteDtos.ShareTokenDto;
import com.project.iot_spring.route.RouteQueries.RouteFilter;
import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import com.project.iot_spring.web.TimeParams;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService routeService;
    private final ZoneId zone;

    public RouteController(RouteService routeService, @Value("${app.timezone:Europe/Warsaw}") String timezone) {
        this.routeService = routeService;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * Paged list. {@code q} searches name, description, id and device name; {@code from}/{@code to} filter
     * by start time; {@code sort} = startedAt (default) | distance | maxUsv | samples | name;
     * {@code dir} = desc (default) | asc; NULL values always last.
     */
    @GetMapping
    public PageResponse<RouteDto> findAll(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) String deviceId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String sort,
                                          @RequestParam(required = false) String dir,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size,
                                          Authentication authentication) {
        RouteFilter filter = new RouteFilter(q, deviceId,
                TimeParams.lowerBound(from, "from", zone), TimeParams.upperBound(to, "to", zone), sort, dir);
        return routeService.findPage(filter, Paging.of(page, size), Auth.isAdmin(authentication));
    }

    @GetMapping("/{routeId}")
    public RouteDto find(@PathVariable Long routeId, Authentication authentication) {
        return routeService.find(routeId, Auth.isAdmin(authentication));
    }

    @PatchMapping("/{routeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public RouteDto update(@PathVariable Long routeId, @Valid @RequestBody RouteUpdateRequest request) {
        return routeService.update(routeId, request);
    }

    @DeleteMapping("/{routeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long routeId) {
        routeService.delete(routeId);
    }

    /** Samples sorted by time; {@code after} (ISO instant) returns only newer ones. */
    @GetMapping("/{routeId}/measurements")
    public List<MeasurementDto> measurements(@PathVariable Long routeId,
                                             @RequestParam(required = false) String after) {
        return routeService.findMeasurements(routeId, TimeParams.lowerBound(after, "after", zone));
    }

    @DeleteMapping("/{routeId}/measurements/{measurementId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMeasurement(@PathVariable Long routeId, @PathVariable Long measurementId) {
        routeService.deleteMeasurement(routeId, measurementId);
    }

    @GetMapping("/{routeId}/markers")
    public List<MarkerDto> markers(@PathVariable Long routeId) {
        return routeService.findMarkers(routeId);
    }

    @PostMapping("/{routeId}/markers")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public MarkerDto addMarker(@PathVariable Long routeId, @Valid @RequestBody MarkerCreateRequest request) {
        return routeService.addMarker(routeId, request);
    }

    @DeleteMapping("/{routeId}/markers/{markerId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMarker(@PathVariable Long routeId, @PathVariable Long markerId) {
        routeService.deleteMarker(routeId, markerId);
    }

    /** Creates a public read-only link (or returns the existing one). */
    @PostMapping("/{routeId}/share")
    @PreAuthorize("hasRole('ADMIN')")
    public ShareTokenDto share(@PathVariable Long routeId) {
        return new ShareTokenDto(routeService.share(routeId));
    }

    @DeleteMapping("/{routeId}/share")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unshare(@PathVariable Long routeId) {
        routeService.unshare(routeId);
    }
}
