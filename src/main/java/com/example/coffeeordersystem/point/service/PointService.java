package com.example.coffeeordersystem.point.service;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.exception.ErrorCode;
import com.example.coffeeordersystem.common.idempotency.IdempotencyChecker;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import com.example.coffeeordersystem.point.entity.PointTransaction;
import com.example.coffeeordersystem.point.entity.PointTransactionType;
import com.example.coffeeordersystem.point.entity.UserPoint;
import com.example.coffeeordersystem.point.repository.PointTransactionRepository;
import com.example.coffeeordersystem.point.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * docs/point-policy.md 기준 스켈레톤.
 * userId 기준 요청 진입 직렬화(최초 충전 동시 요청 레이스 방지)는 PointChargeService가 맡고,
 * 이 클래스는 그 안에서 실행되는 트랜잭션 본체(잔액 검증 + 원장 insert)만 담당한다.
 */
@Service
@RequiredArgsConstructor
public class PointService {

    private static final int MIN_CHARGE_AMOUNT = 1;
    private static final int MAX_CHARGE_AMOUNT = 1_000_000;
    private static final String ENDPOINT = "POST /api/points/charge";

    private final UserPointRepository userPointRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final IdempotencyChecker idempotencyChecker;

    @Transactional
    public PointChargeResponse charge(String idempotencyKey, PointChargeRequest request) {
        // 비즈니스 로직과 같은 트랜잭션 안에서 먼저 검사한다(ADR-006).
        idempotencyChecker.requireFirstRequest(idempotencyKey, ENDPOINT);

        validateChargeAmount(request.amount());

        // 최초 충전(row 없음) 동시 요청 레이스는 PointChargeService의 userId 락으로
        // 이 메서드에 진입하는 시점부터 이미 직렬화돼 있다. 따라서 이 시점에는 같은 userId로
        // 두 스레드가 동시에 findByUserIdForUpdate를 호출할 수 없다.
        UserPoint userPoint = userPointRepository.findByUserIdForUpdate(request.userId())
                .orElseGet(() -> userPointRepository.save(new UserPoint(request.userId(), 0)));

        userPoint.charge(request.amount());

        pointTransactionRepository.save(new PointTransaction(
                request.userId(),
                PointTransactionType.CHARGE,
                request.amount(),
                userPoint.getBalance(),
                LocalDateTime.now()
        ));

        return new PointChargeResponse(userPoint.getUserId(), userPoint.getBalance());
    }

    private void validateChargeAmount(int amount) {
        if (amount < MIN_CHARGE_AMOUNT || amount > MAX_CHARGE_AMOUNT) {
            throw new BusinessException(ErrorCode.INVALID_CHARGE_AMOUNT);
        }
    }
}
