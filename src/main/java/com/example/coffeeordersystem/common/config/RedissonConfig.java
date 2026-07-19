package com.example.coffeeordersystem.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * docs/order-policy.md의 Redisson 분산 락(userId 기준 진입 제어)용 클라이언트.
 * 단일 Redis 인스턴스 모드(로컬 bootRun, Testcontainers 통합 테스트 기본값).
 * `redisson.mode=sentinel`이면 대신 {@link RedissonSentinelConfig}가 활성화된다
 * (docs/adr/ADR-009 참고).
 */
@Configuration
@ConditionalOnProperty(name = "redisson.mode", havingValue = "single", matchIfMissing = true)
public class RedissonConfig {

    @Value("${redisson.address}")
    private String redissonAddress;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress(redissonAddress);
        return Redisson.create(config);
    }
}
