package com.example.coffeeordersystem.ranking.controller;

import com.example.coffeeordersystem.ranking.dto.PopularMenuResponse;
import com.example.coffeeordersystem.ranking.service.RankingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
