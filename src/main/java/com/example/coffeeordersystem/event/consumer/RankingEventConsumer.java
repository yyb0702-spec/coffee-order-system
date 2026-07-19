package com.example.coffeeordersystem.event.consumer;

import com.example.coffeeordersystem.event.dto.OrderCompletedEvent;
import com.example.coffeeordersystem.event.entity.ProcessedEvent;
import com.example.coffeeordersystem.event.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@Slf4j
public class RankingEventConsumer {

    private static final String CONSUMER_GROUP = "ranking-consumer-group";
    private static final String KEY_PREFIX = "popular:menus:";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final ProcessedEventRepository processedEventRepository;
    private final StringRedisTemplate redisTemplate;

    @KafkaListener(topics = "${app.kafka.topic.order-completed}", groupId = CONSUMER_GROUP)
    @Transactional
    public void consume(OrderCompletedEvent event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            log.info("이미 처리된 이벤트입니다. eventId={}", event.eventId());
            return;
        }

        // DB insert(원장/ledger)를 Redis 반영보다 먼저 수행한다 (#100 대응).
        // Redis ZINCRBY는 DB 트랜잭션에 묶이지 않아 롤백되지 않으므로, 순서가 반대이면
        // "Redis 증가 성공 -> DB save 실패 -> 트랜잭션 롤백 -> Kafka 재시도 -> Redis 재증가"
        // 시나리오에서 이중 카운트가 발생한다. DB save를 먼저 두면 save가 실패할 때
        // Redis에는 아직 손대지 않은 상태이고, save가 성공한 뒤 Redis 증가가 실패해도
        // 예외가 전파되며 트랜잭션이 롤백돼 다음 재시도가 두 작업을 처음부터 함께 재현한다.
        processedEventRepository.save(new ProcessedEvent(
                event.eventId(),
                "OrderCompletedEvent",
                CONSUMER_GROUP,
                LocalDateTime.now()
        ));

        String dateKey = KEY_PREFIX + event.orderedAt().toLocalDate().format(DATE_FORMAT);
        redisTemplate.opsForZSet().incrementScore(dateKey, String.valueOf(event.menuId()), 1);
    }
}
