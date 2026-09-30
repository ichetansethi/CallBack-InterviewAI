package com.callback.session;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.callback")
public class SessionHistoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SessionHistoryServiceApplication.class, args);
    }

}
