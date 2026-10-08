package com.project.iot_spring.user;

public class LoginBlockedException extends RuntimeException {

    public LoginBlockedException(String message) {
        super(message);
    }
}
