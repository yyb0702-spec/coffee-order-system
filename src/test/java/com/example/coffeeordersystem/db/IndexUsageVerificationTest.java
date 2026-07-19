package com.example.coffeeordersystem.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 쿼리 인덱스 사용 검증 (참고 저장소의 QueryDSL+EXPLAIN 검증 방식에서 아이디어를 가져옴).
 * QueryDSL은 도입하지 않았다 — 이 스코프에서 검증하려는 건
 * "쿼리가 인덱스를 타는가"이지 쿼리 작성 방식(JPQL vs QueryDSL) 자체가 아니고,
 * Gradle 애노테이션 프로세서 설정을 이 환경에서 실제로 컴파일 검증할 수 없어
 * 빌드를 깨뜨릴 위험을 감수하고 싶지 않았다. 대신 Repository의 JPQL이 생성하는 SQL과
 * 동일한 형태의 원본 SQL을 Testcontainers MySQL에 대해 EXPLAIN으로 직접 검증한다.
 * <p>
 * Spring 컨텍스트 없이(Flyway + JDBC만으로) 가볍게 동작하도록 구성했다.
 */
@Testcontainers
class IndexUsageVerificationTest {

    private static final int PAID_ORDER_COUNT = 2000;
    private static final int CANCELLED_ORDER_COUNT = 500;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("coffee_order_system")
            .withUsername("root")
            .withPassword("12345678");

    static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        dataSource.setDriverClassName(mysql.getDriverClassName());
        jdbcTemplate = new JdbcTemplate(dataSource);

        seedData();
    }

    private static void seedData() {
        // V2__seed_menu.sql이 이미 메뉴를 시드하므로 새로 insert하지 않고, 실제 존재하는
        // 메뉴 ID를 그대로 재사용한다 (직접 insert하면 id 1~5가 이미 쓰여 있어 6~10번대의
        // 쓰이지 않는 행만 늘어난다).
        List<Long> menuIds = jdbcTemplate.queryForList("SELECT id FROM menu ORDER BY id", Long.class);

        jdbcTemplate.update("INSERT INTO user_point (user_id, balance) VALUES (?, ?)", 1L, 10_000_000);

        LocalDateTime now = LocalDateTime.now();
        List<Object[]> paidRows = new ArrayList<>(PAID_ORDER_COUNT);
        for (int i = 0; i < PAID_ORDER_COUNT; i++) {
            long menuId = menuIds.get(i % menuIds.size());
            paidRows.add(new Object[] {1L, menuId, 4000, now.minusHours(i)});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO orders (user_id, menu_id, paid_amount, status, ordered_at) VALUES (?, ?, ?, 'PAID', ?)",
                paidRows);

        List<Object[]> cancelledRows = new ArrayList<>(CANCELLED_ORDER_COUNT);
        for (int i = 0; i < CANCELLED_ORDER_COUNT; i++) {
            long menuId = menuIds.get(i % menuIds.size());
            cancelledRows.add(new Object[] {1L, menuId, 4000, now.minusHours(i)});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO orders (user_id, menu_id, paid_amount, status, ordered_at) VALUES (?, ?, ?, 'CANCELLED', ?)",
                cancelledRows);
    }

    /**
     * UserPointRepository#findByUserIdForUpdate (SELECT ... FOR UPDATE)가 uk_user_point_user_id를 타는지 확인한다.
     * 이 락은 OrderPaymentProcessor/PointService의 동시성 제어 핵심 경로이므로 풀스캔이면 다중 인스턴스 환경에서
     * 락 대기 시간이 크게 늘어난다.
     */
    @Test
    void userPointLookupForUpdate_usesUniqueIndex() {
        List<Map<String, Object>> explain = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id, user_id, balance FROM user_point WHERE user_id = ? FOR UPDATE", 1L);

        Map<String, Object> row = explain.get(0);
        Object key = row.get("key");

        assertNotNull(key, "user_id 조건 조회가 인덱스를 타지 않고 풀스캔되고 있습니다: " + row);
        assertEquals("uk_user_point_user_id", key);
    }

    /**
     * OrderRepository#findTopPaidMenuOrderCounts가 idx_orders_status_ordered_at을 타는지 확인한다.
     * 인기 메뉴 DB 폴백/재구성(RankingService)이 이 쿼리를 쓰므로, 풀스캔이면 랭킹 데이터가 커질수록
     * Redis 장애 시 DB 폴백 응답이 느려진다.
     */
    @Test
    void popularMenuAggregation_usesStatusOrderedAtIndex() {
        LocalDateTime from = LocalDateTime.now().minusDays(7);
        LocalDateTime to = LocalDateTime.now();

        List<Map<String, Object>> explain = jdbcTemplate.queryForList(
                "EXPLAIN SELECT menu_id, COUNT(*) AS order_count FROM orders "
                        + "WHERE status = 'PAID' AND ordered_at >= ? AND ordered_at < ? "
                        + "GROUP BY menu_id ORDER BY COUNT(*) DESC, menu_id ASC",
                from, to);

        Map<String, Object> row = explain.get(0);
        Object key = row.get("key");

        assertNotNull(key, "인기 메뉴 집계 쿼리가 인덱스를 타지 않고 풀스캔되고 있습니다: " + row);
        assertEquals("idx_orders_status_ordered_at", key);
    }
}
