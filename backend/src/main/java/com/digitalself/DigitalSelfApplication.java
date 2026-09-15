package com.digitalself;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DigitalSelfApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalSelfApplication.class, args);
    }
}
