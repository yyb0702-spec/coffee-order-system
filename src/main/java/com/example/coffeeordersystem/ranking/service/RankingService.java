package com.example.coffeeordersystem.ranking.service;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.repository.MenuOrderCount;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.ranking.dto.PopularMenuResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 인기 메뉴(최근 7일 top3) 조회. docs/popular-menu-policy.md 참고.
 *
 * 기본 조회 원천은 Redis ZSET(파생 데이터, 일자별 키 popular:menus:{date})이며,
 * 값이 없거나 신뢰할 수 없으면 DB 집계 쿼리(원천 데이터)로 폴백한다.
 * ZSET 갱신은 이 서비스가 아니라 event.consumer.RankingEventConsumer가 담당한다.
 */
@Service
@RequiredArgsConstructor
public class RankingService {

    private static final String KEY_PREFIX = "popular:menus:";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int RECENT_DAYS = 7;
    private static final int TOP_N = 3;

    private final StringRedisTemplate redisTemplate;
    private final OrderRepository orderRepository;
    private final MenuRepository menuRepository;

    public List<PopularMenuResponse> getPopularMenus() {
        List<Map.Entry<Long, Long>> ranked = readFromRedis();

        if (ranked.isEmpty()) {
            // TODO: Redis 장애/초기 기동 등으로 신뢰할 수 없는 상태 판별 로직을 보강한다.
            ranked = readFromDatabase();
        }

        return toResponses(ranked);
    }

    private List<Map.Entry<Long, Long>> readFromRedis() {
        List<String> dailyKeys = recentDailyKeys();
        String tempKey = KEY_PREFIX + "temp:" + UUID.randomUUID();

        redisTemplate.opsForZSet().unionAndStore(dailyKeys.get(0), dailyKeys.subList(1, dailyKeys.size()), tempKey);
        try {
            // 동점 처리(주문수 내림차순, 메뉴ID 오름차순)를 위해 넉넉히 후보를 가져와 애플리케이션에서 재정렬한다.
            Set<ZSetOperations.TypedTuple<String>> candidates =
                    redisTemplate.opsForZSet().reverseRangeWithScores(tempKey, 0, 9);

            if (candidates == null || candidates.isEmpty()) {
                return List.of();
            }

            return candidates.stream()
                    .map(tuple -> Map.entry(Long.valueOf(tuple.getValue()), tuple.getScore().longValue()))
                    .sorted(Comparator.<Map.Entry<Long, Long>>comparingLong(e -> -e.getValue())
                            .thenComparing(Map.Entry::getKey))
                    .limit(TOP_N)
                    .toList();
        } finally {
            redisTemplate.delete(tempKey);
        }
    }

    private List<Map.Entry<Long, Long>> readFromDatabase() {
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(RECENT_DAYS);

        List<MenuOrderCount> counts = orderRepository.findTopPaidMenuOrderCounts(
                from, to, PageRequest.of(0, TOP_N));

        return counts.stream()
                .map(c -> Map.entry(c.getMenuId(), c.getOrderCount()))
                .toList();
    }

    private List<PopularMenuResponse> toResponses(List<Map.Entry<Long, Long>> ranked) {
        List<PopularMenuResponse> responses = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<Long, Long> entry : ranked) {
            Menu menu = menuRepository.findById(entry.getKey()).orElse(null);
            String menuName = menu != null ? menu.getName() : "UNKNOWN";
            responses.add(new PopularMenuResponse(rank++, entry.getKey(), menuName, entry.getValue()));
        }
        return responses;
    }

    private List<String> recentDailyKeys() {
        List<String> keys = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < RECENT_DAYS; i++) {
            keys.add(KEY_PREFIX + today.minusDays(i).format(DATE_FORMAT));
        }
        return keys;
    }
}
