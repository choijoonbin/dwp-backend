package com.dwp.services.time;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(info = @Info(title = "DWP Time Service API", version = "1.0.0"))
@SpringBootApplication
public class TimeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimeServerApplication.class, args);
    }
}
