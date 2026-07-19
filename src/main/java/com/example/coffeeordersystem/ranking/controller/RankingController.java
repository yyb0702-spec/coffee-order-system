package com.example.coffeeordersystem.ranking.controller;

import com.example.coffeeordersystem.ranking.dto.PopularMenuResponse;
import com.example.coffeeordersystem.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/menus/popular")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;

    @GetMapping
    public List<PopularMenuResponse> getPopularMenus() {
        return rankingService.getPopularMenus();
    }

    /**
     * Redis 유실/장애 이후 운영자가 수동으로 트리거하는 복구용 엔드포인트 (ADR-004).
     * 일반 사용자 트래픽 경로가 아니므로 인증/인가는 이번 스코프에서 다루지 않는다.
     */
    @PostMapping("/rebuild")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rebuildFromDb() {
        rankingService.rebuildFromDb();
    }
}
