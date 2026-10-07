package com.dwp.services.payroll;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(info = @Info(title = "DWP Payroll Service API", version = "1.0.0"))
@SpringBootApplication
public class PayrollServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayrollServerApplication.class, args);
    }
}
