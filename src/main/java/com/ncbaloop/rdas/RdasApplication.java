package com.ncbaloop.rdas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RdasApplication {
    public static void main(String[] args) {
        SpringApplication.run(RdasApplication.class, args);
    }
}
