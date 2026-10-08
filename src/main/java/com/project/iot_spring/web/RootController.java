package com.project.iot_spring.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {

    @GetMapping({"/", "/api"})
    public Map<String, String> root() {
        return Map.of("status", "API server running");
    }
}
