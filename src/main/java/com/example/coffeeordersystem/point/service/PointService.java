package com.example.coffeeordersystem.point.service;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.exception.ErrorCode;
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
 * TODO: Idempotency-Key 처리는 컨트롤러 or AOP에서 공통으로 감싸고,
 *       DUPLICATE_REQUEST 판단 후 이 서비스로 위임하는 구조를 별도 이슈로 구현한다.
 */
@Service
@RequiredArgsConstructor
public class PointService {

    private static final int MIN_CHARGE_AMOUNT = 1;
    private static final int MAX_CHARGE_AMOUNT = 1_000_000;

    private final UserPointRepository userPointRepository;
    private final PointTransactionRepository pointTransactionRepository;

    @Transactional
    public PointChargeResponse charge(PointChargeRequest request) {
        validateChargeAmount(request.amount());

        // TODO: 동시 최초-충전 요청(같은 userId, row 없음) 경합은 이번 스켈레톤 범위 밖.
        //       unique 제약(user_id) 위반 시 재조회하는 재시도 로직을 구현 단계에서 추가한다.
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
