package com.wannaverse;

import com.wannaverse.config.SecretsBootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories
@EnableAsync
@EnableScheduling
public class Main {
    public static void main(String[] args) {
        SecretsBootstrap.apply();
        SpringApplication.run(Main.class, args);
    }
}
