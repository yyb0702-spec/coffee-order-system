package com.example.coffeeordersystem.event.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ProcessedEvent(ledger) 보존기간 정리를 스케줄을 기다리지 않고 즉시 트리거하는 운영 엔드포인트.
 */
@RestController
@RequestMapping("/api/admin/processed-events")
@RequiredArgsConstructor
public class ProcessedEventRetentionController {

    private final ProcessedEventRetentionService processedEventRetentionService;

    @PostMapping("/purge")
    public Map<String, Long> purge() {
        long deletedCount = processedEventRetentionService.purgeOldEntries();
        return Map.of("deletedCount", deletedCount);
    }
}
