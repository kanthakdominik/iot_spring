package com.project.iot_spring.web.controller;

import com.project.iot_spring.web.dto.LoginDTO;
import com.project.iot_spring.web.dto.UserDTO;
import com.project.iot_spring.web.service.UserService;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@AllArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserService userService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody LoginDTO loginDTO, HttpSession session) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(loginDTO.getUsername(), loginDTO.getPassword())
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

            return ResponseEntity.ok(Map.of(
                    "message", "Login successful",
                    "username", authentication.getName(),
                    "sessionId", session.getId()
            ));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
    }

    @GetMapping("/username")
    public ResponseEntity<Map<String, String>> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && !"anonymousUser".equals(authentication.getPrincipal())) {
                UserDTO userDTO = userService.getCurrentUserInfo();
                return ResponseEntity.ok(Map.of("username", userDTO.getUsername()));
            }
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Unauthorized",
                    "message", "User not authenticated"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Unauthorized",
                    "message", "Authentication failed"
            ));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpSession session) {
        SecurityContextHolder.clearContext();
        session.invalidate();
        return ResponseEntity.ok(Map.of("message", "Logout successful"));
    }
}