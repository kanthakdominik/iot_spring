package com.project.iot_spring.alert;

import com.project.iot_spring.web.PageResponse;
import com.project.iot_spring.web.PageResponse.Paging;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;
    private final AlertRuleService ruleService;

    /** {@code state=open|all} (default all), newest first. */
    @GetMapping("/api/alerts")
    public PageResponse<AlertDto> alerts(@RequestParam(defaultValue = "all") String state,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        if (!"open".equals(state) && !"all".equals(state)) {
            throw new IllegalArgumentException("Parameter 'state' must be 'open' or 'all'");
        }
        return alertService.find("open".equals(state), Paging.of(page, size));
    }

    @GetMapping("/api/alerts/count")
    public Map<String, Long> count() {
        return Map.of("open", alertService.countOpen());
    }

    @PostMapping("/api/alerts/{alertId}/ack")
    @PreAuthorize("hasRole('ADMIN')")
    public AlertDto acknowledge(@PathVariable long alertId, Authentication authentication) {
        return alertService.acknowledge(alertId, authentication.getName());
    }

    @PostMapping("/api/alerts/ack-all")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> acknowledgeAll(Authentication authentication) {
        return Map.of("acknowledged", alertService.acknowledgeAll(authentication.getName()));
    }

    @GetMapping("/api/alert-rules")
    public List<AlertRuleDto> rules() {
        return ruleService.findAll();
    }

    @PostMapping("/api/alert-rules")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleDto create(@Valid @RequestBody AlertRuleDto rule) {
        return ruleService.create(rule);
    }

    @PutMapping("/api/alert-rules/{ruleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AlertRuleDto update(@PathVariable long ruleId, @Valid @RequestBody AlertRuleDto rule) {
        return ruleService.update(ruleId, rule);
    }

    @DeleteMapping("/api/alert-rules/{ruleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long ruleId) {
        ruleService.delete(ruleId);
    }
}
