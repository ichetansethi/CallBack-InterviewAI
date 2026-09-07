package com.callback.compatibility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.callback")
public class CompatibilityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompatibilityServiceApplication.class, args);
    }

}
