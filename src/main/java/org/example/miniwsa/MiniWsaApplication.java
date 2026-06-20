package org.example.miniwsa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MiniWsaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniWsaApplication.class, args);
    }
}
