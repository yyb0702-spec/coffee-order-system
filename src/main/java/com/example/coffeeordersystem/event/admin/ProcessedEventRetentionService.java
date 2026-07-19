package com.example.coffeeordersystem.event.admin;

import com.example.coffeeordersystem.event.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ledger(ProcessedEvent) 보존기간 정리.
 * <p>
 * ProcessedEvent는 Kafka Consumer의 eventId 기준 멱등 처리와 DLT 재발행 모두가
 * 공유하는 단일 ledger다. 무한정 쌓아두면 테이블이 계속 커지므로, 재시도/재발행이 현실적으로
 * 일어날 수 있는 기간(RETENTION_DAYS)보다 오래된 항목은 주기적으로 정리한다.
 * <p>
 * 보존기간을 지난 뒤에는 "이미 처리된 이벤트"라는 사실 자체를 잊게 되지만, DLT에 남아있지 않은
 * (=이미 성공 처리된) 이벤트가 그렇게 뒤늦게 다시 발행될 일은 정상 운영에서는 없다고 본다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessedEventRetentionService {

    private static final int RETENTION_DAYS = 30;

    private final ProcessedEventRepository processedEventRepository;

    // 매일 새벽 3시 30분에 정리한다 (트래픽이 적은 시간대).
    // 스케줄러는 프록시 빈을 통해 호출하므로 같은 메서드에 @Transactional을 함께 둬도
    // self-invocation으로 인해 트랜잭션이 무시되는 문제가 없다
    // (ProcessedEventRetentionController#purge에서 직접 호출할 때도 마찬가지로 정상 적용된다).
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public long purgeOldEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("보존기간({}일)이 지난 처리 이력 {}건을 정리했습니다.", RETENTION_DAYS, deleted);
        }
        return deleted;
    }
}
