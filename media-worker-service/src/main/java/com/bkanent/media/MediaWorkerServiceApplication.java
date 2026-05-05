package com.bkanent.media;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableDubbo
@SpringBootApplication
public class MediaWorkerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaWorkerServiceApplication.class, args);
    }
}
