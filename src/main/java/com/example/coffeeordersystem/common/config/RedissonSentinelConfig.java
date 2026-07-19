package com.example.coffeeordersystem.common.config;

import java.util.List;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis Sentinel 기반 Redisson 클라이언트 (docs/adr/ADR-009 참고).
 * `redisson.mode=sentinel`일 때만 활성화되며, docker-compose의 컨테이너화된 app 서비스가
 * 이 모드로 뜬다. Master 장애 시 Sentinel 쿼럼 합의로 Slave가 승격되고, 이 클라이언트는
 * 코드 변경 없이 새 Master 주소로 자동 재연결한다.
 *
 * 로컬 ./gradlew bootRun이나 Testcontainers 통합 테스트는 이 프로퍼티를 설정하지 않으므로
 * 계속 {@link RedissonConfig}(단일 서버 모드)를 사용한다.
 */
@Configuration
@ConditionalOnProperty(name = "redisson.mode", havingValue = "sentinel")
public class RedissonSentinelConfig {

    @Value("${redisson.sentinel.master-name}")
    private String masterName;

    @Value("#{'${redisson.sentinel.nodes}'.split(',')}")
    private List<String> sentinelNodes;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSentinelServers()
                .setMasterName(masterName)
                .addSentinelAddress(sentinelNodes.toArray(new String[0]));
        return Redisson.create(config);
    }
}
