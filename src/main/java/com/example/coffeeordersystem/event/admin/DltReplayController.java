package com.example.coffeeordersystem.event.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * DLT(order.completed.DLT) 재발행 운영 엔드포인트.
 * 일반 사용자 트래픽 경로가 아니므로 인증/인가는 이번 스코프에서 다루지 않는다
 * (RankingController#rebuild와 동일한 전제).
 */
@RestController
@RequestMapping("/api/admin/dlt")
@RequiredArgsConstructor
public class DltReplayController {

    private static final int DEFAULT_MAX_RECORDS = 100;

    private final DltReplayService dltReplayService;

    @PostMapping("/replay")
    public Map<String, Integer> replay(
            @RequestParam(name = "maxRecords", defaultValue = "" + DEFAULT_MAX_RECORDS) int maxRecords) {
        int replayedCount = dltReplayService.replay(maxRecords);
        return Map.of("replayedCount", replayedCount);
    }
}
