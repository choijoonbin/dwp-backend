package com.dwp.services.mail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.dwp.services.mail")
public class MailServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailServiceApplication.class, args);
    }
}

