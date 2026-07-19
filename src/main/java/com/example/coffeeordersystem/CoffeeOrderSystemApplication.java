package com.example.coffeeordersystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling: ProcessedEvent(랭킹 ledger) 보존기간 정리 배치(ProcessedEventRetentionService)에 사용한다.
@SpringBootApplication
@EnableScheduling
public class CoffeeOrderSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoffeeOrderSystemApplication.class, args);
    }

}
