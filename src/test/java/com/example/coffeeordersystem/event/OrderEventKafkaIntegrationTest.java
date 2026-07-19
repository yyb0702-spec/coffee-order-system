package com.example.coffeeordersystem.event;

import com.example.coffeeordersystem.event.dto.OrderCompletedEvent;
import com.example.coffeeordersystem.event.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ADR-003 이벤트 발행에 Kafka 사용 / ADR-004 Redis ZSET 인기 메뉴 랭킹.
 * <p>
 * OrderEventProducer가 발행을 담당하는 실제 컨트롤러/서비스 경로 대신,
 * "Kafka 발행 -> RankingEventConsumer 소비 -> Redis ZSET 반영" 구간 자체를
 * 좁게 겨냥해 검증한다. OrderCompletedEvent를 직접 KafkaTemplate으로 발행해
 * 실제 KafkaListener(RankingEventConsumer)가 소비하는지, eventId 기준 멱등 처리가
 * 중복 소비를 막는지를 확인한다.
 */
@Testcontainers
@SpringBootTest
class OrderEventKafkaIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("coffee_order_system")
            .withUsername("root")
            .withPassword("12345678");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("redisson.address",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));

        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Value("${app.kafka.topic.order-completed}")
    private String orderCompletedTopic;

    @BeforeEach
    void cleanUp() {
        processedEventRepository.deleteAll();
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void publishedEvent_isConsumedAndIncrementsRankingZSet() throws Exception {
        LocalDateTime orderedAt = LocalDateTime.now();
        Long menuId = 100L;
        OrderCompletedEvent event = new OrderCompletedEvent(
                UUID.randomUUID().toString(), 1L, 1L, menuId, 4000, orderedAt);

        kafkaTemplate.send(orderCompletedTopic, String.valueOf(event.userId()), event)
                .get(10, TimeUnit.SECONDS);

        awaitUntil(() -> processedEventRepository.existsByEventId(event.eventId()), Duration.ofSeconds(10));

        String dateKey = "popular:menus:" + orderedAt.toLocalDate();
        Double score = redisTemplate.opsForZSet().score(dateKey, String.valueOf(menuId));
        assertNotNull(score, "Kafka로 발행한 이벤트가 Redis ZSET에 반영되지 않았습니다.");
        assertEquals(1.0, score);
    }

    @Test
    void duplicateEventId_isConsumedOnlyOnce() throws Exception {
        LocalDateTime orderedAt = LocalDateTime.now();
        Long menuId = 200L;
        OrderCompletedEvent event = new OrderCompletedEvent(
                UUID.randomUUID().toString(), 2L, 1L, menuId, 4000, orderedAt);

        kafkaTemplate.send(orderCompletedTopic, String.valueOf(event.userId()), event)
                .get(10, TimeUnit.SECONDS);
        awaitUntil(() -> processedEventRepository.existsByEventId(event.eventId()), Duration.ofSeconds(10));

        // 네트워크 재시도 등으로 같은 eventId를 담은 메시지가 다시 발행되는 상황을 재현한다.
        kafkaTemplate.send(orderCompletedTopic, String.valueOf(event.userId()), event)
                .get(10, TimeUnit.SECONDS);
        // 두 번째 메시지가 소비될 시간을 넉넉히 준 뒤에도 반영 결과가 그대로인지 확인한다.
        Thread.sleep(3000);

        String dateKey = "popular:menus:" + orderedAt.toLocalDate();
        Double score = redisTemplate.opsForZSet().score(dateKey, String.valueOf(menuId));
        assertNotNull(score);
        assertEquals(1.0, score, "중복 eventId는 랭킹 점수에 두 번 반영되면 안 됩니다.");
        assertEquals(1, processedEventRepository.count(), "processed_event에도 한 번만 기록돼야 합니다.");
    }

    private void awaitUntil(Supplier<Boolean> condition, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("조건이 " + timeout.toSeconds() + "초 안에 충족되지 않았습니다.");
    }
}
