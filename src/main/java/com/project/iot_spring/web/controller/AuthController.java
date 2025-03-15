package com.project.iot_spring.web.controller;

import com.project.iot_spring.web.dto.LoginDTO;
import com.project.iot_spring.web.dto.UserDTO;
import com.project.iot_spring.web.service.UserService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "http://localhost:4200")
@AllArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginDTO loginDTO) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginDTO.getUsername(), loginDTO.getPassword())
            );
            return ResponseEntity.ok(Map.of(
                    "message", "Login successful",
                    "username", authentication.getName()
            ));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
    }

    @GetMapping("/username")
    public ResponseEntity<Map<String, String>> getCurrentUser() {
        try {
            UserDTO userDTO = userService.getCurrentUserInfo();
            return ResponseEntity.ok(Map.of("username", userDTO.getUsername()));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
    }
}