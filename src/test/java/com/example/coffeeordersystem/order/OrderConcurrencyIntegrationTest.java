package com.example.coffeeordersystem.order;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.idempotency.IdempotencyKeyRepository;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.dto.OrderRequest;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.order.service.OrderService;
import com.example.coffeeordersystem.point.entity.UserPoint;
import com.example.coffeeordersystem.point.repository.PointTransactionRepository;
import com.example.coffeeordersystem.point.repository.UserPointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/order-policy.md / ADR-002·ADR-007에 문서화된 동시성 시나리오를 그대로 검증한다.
 * <p>
 * 시나리오: 초기 포인트 10,000P, 메뉴 가격 4,000P인 사용자 1명에게 동시에 10건의 주문을 넣는다.
 * 기대 결과: 성공 2건, 실패(INSUFFICIENT_POINT) 8건, 최종 잔액 2,000P, 잔액은 절대 음수가 되지 않는다.
 * <p>
 * 실제 MySQL의 SELECT ... FOR UPDATE 락 동작을 검증해야 하므로 H2가 아닌 Testcontainers MySQL을 사용한다.
 * Redisson 분산 락(1차 방어선)과 Kafka 이벤트 발행(주문 커밋 후 AFTER_COMMIT)까지 실제 인프라로 띄워
 * OrderService → OrderPaymentProcessor 전체 경로를 통합 테스트한다.
 */
@Testcontainers
@SpringBootTest
class OrderConcurrencyIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final int INITIAL_BALANCE = 10_000;
    private static final int MENU_PRICE = 4_000;
    private static final int EXPECTED_SUCCESS = 2;
    private static final int EXPECTED_FAIL = 8;
    private static final int EXPECTED_FINAL_BALANCE = 2_000;

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
    private OrderService orderService;
    @Autowired
    private MenuRepository menuRepository;
    @Autowired
    private UserPointRepository userPointRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PointTransactionRepository pointTransactionRepository;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private Long userId;
    private Long menuId;

    @BeforeEach
    void setUp() {
        // FK 순서를 지켜 초기화한다: orders -> point_transaction -> user_point -> menu.
        orderRepository.deleteAll();
        pointTransactionRepository.deleteAll();
        userPointRepository.deleteAll();
        menuRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();

        Menu menu = menuRepository.save(new Menu("아메리카노", MENU_PRICE));
        menuId = menu.getId();

        UserPoint userPoint = userPointRepository.save(new UserPoint(1L, INITIAL_BALANCE));
        userId = userPoint.getUserId();
    }

    @Test
    void concurrentOrders_onlyAffordableCountSucceeds() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String idempotencyKey = "concurrency-test-order-" + i;
            pool.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    orderService.placeOrder(idempotencyKey, new OrderRequest(userId, menuId));
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 모든 스레드가 준비된 뒤 동시에 요청을 쏘아 최대한 같은 시점의 경합을 만든다.
        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean finishedInTime = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(finishedInTime, "모든 주문 요청이 30초 안에 끝나지 않았습니다.");
        assertEquals(EXPECTED_SUCCESS, successCount.get(), "성공 건수가 기대와 다릅니다.");
        assertEquals(EXPECTED_FAIL, failCount.get(), "실패 건수가 기대와 다릅니다.");

        UserPoint finalUserPoint = userPointRepository.findByUserId(userId).orElseThrow();
        assertEquals(EXPECTED_FINAL_BALANCE, finalUserPoint.getBalance(), "최종 잔액이 기대와 다릅니다.");
        assertTrue(finalUserPoint.getBalance() >= 0, "잔액이 음수가 될 수 없습니다.");

        long paidOrderCount = orderRepository.count();
        assertEquals(EXPECTED_SUCCESS, paidOrderCount, "실제로 저장된 주문 건수가 성공 건수와 다릅니다.");
    }
}
