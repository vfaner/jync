package com.synctool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.synctool.config.SyncProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SyncProperties.class)
public class SyncToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyncToolApplication.class, args);
    }
}
