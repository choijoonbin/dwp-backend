package com.dwp.services.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackages = "com.dwp.services.approval")
public class ApprovalServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApprovalServiceApplication.class, args);
    }
}

