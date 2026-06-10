package com.test.eventgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EventGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}
