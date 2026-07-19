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

        String dateKey = KEY_PREFIX + event.orderedAt().toLocalDate().format(DATE_FORMAT);
        redisTemplate.opsForZSet().incrementScore(dateKey, String.valueOf(event.menuId()), 1);

        processedEventRepository.save(new ProcessedEvent(
                event.eventId(),
                "OrderCompletedEvent",
                CONSUMER_GROUP,
                LocalDateTime.now()
        ));
    }
}
