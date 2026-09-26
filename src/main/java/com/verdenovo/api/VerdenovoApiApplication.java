package com.verdenovo.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VerdenovoApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(VerdenovoApiApplication.class, args);
    }
}
