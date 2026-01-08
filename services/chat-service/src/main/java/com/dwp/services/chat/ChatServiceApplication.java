package com.dwp.services.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.dwp.services.chat")
public class ChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}

