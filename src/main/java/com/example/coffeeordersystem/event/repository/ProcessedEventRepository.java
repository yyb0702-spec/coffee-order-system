package com.example.coffeeordersystem.event.repository;

import com.example.coffeeordersystem.event.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);

    // ledger(멱등 처리 이력) 보존기간 정리용.
    long deleteByProcessedAtBefore(LocalDateTime cutoff);
}
