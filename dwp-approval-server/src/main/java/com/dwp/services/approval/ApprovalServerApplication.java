package com.dwp.services.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApprovalServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApprovalServerApplication.class, args);
    }
}
