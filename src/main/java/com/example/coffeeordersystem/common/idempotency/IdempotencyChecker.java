package com.example.coffeeordersystem.common.idempotency;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * ADR-006 Idempotency Key 도입.
 * "조회 후 삽입"이 아니라 unique 제약 테이블에 즉시 insert를 시도하고, 실패(제약 위반)를
 * DUPLICATE_REQUEST로 변환하는 방식으로 다수 인스턴스 환경의 TOCTOU 레이스를 피한다.
 * <p>
 * 반드시 호출자의 비즈니스 트랜잭션 안에서 호출해야 한다(별도 트랜잭션으로 분리하지 않는다).
 * 그래야 이후 로직이 실패해 롤백되더라도 idempotency key만 남아 해당 요청이
 * 영구히 재시도 불가능해지는 문제가 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyChecker {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public void requireFirstRequest(String idempotencyKey, String endpoint) {
        try {
            idempotencyKeyRepository.saveAndFlush(
                    new IdempotencyKey(idempotencyKey, endpoint, LocalDateTime.now())
            );
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        }
    }
}
