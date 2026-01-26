package com.dwp.services.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * WebMvcTest 슬라이스 테스트 전용 최소 Application.
 * JPA 미포함으로 "JPA metamodel must not be empty" 방지.
 */
@SpringBootApplication
public class SliceTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(SliceTestApplication.class, args);
    }
}
