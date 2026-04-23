package com.example.coordinationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CoordinationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoordinationServiceApplication.class, args);
    }
}
