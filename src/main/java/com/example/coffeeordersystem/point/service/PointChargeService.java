package com.example.coffeeordersystem.point.service;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.exception.ErrorCode;
import com.example.coffeeordersystem.point.dto.PointChargeRequest;
import com.example.coffeeordersystem.point.dto.PointChargeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 포인트 충전 파사드. order.service.OrderService와 동일한 역할 분리를 따른다:
 * userId 기준 Redisson 분산 락으로 요청 진입을 직렬화한 뒤 PointService(트랜잭션 본체)에 위임한다.
 * <p>
 * 최초 충전(아직 user_point row가 없는 사용자)에 대한 동시 요청은 findByUserIdForUpdate가
 * 아무 것도 잠그지 못해(잠글 row 자체가 없음) DB 비관적 락만으로는 막을 수 없는 레이스였다.
 * 이 락으로 같은 userId의 요청을 앞단에서부터 직렬화해 그 레이스 자체를 없앤다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PointChargeService {

    private static final String LOCK_KEY_PREFIX = "point-lock:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;

    private final RedissonClient redissonClient;
    private final PointService pointService;

    public PointChargeResponse charge(String idempotencyKey, PointChargeRequest request) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + request.userId());

        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.POINT_LOCK_NOT_ACQUIRED);
        }

        if (!acquired) {
            throw new BusinessException(ErrorCode.POINT_LOCK_NOT_ACQUIRED);
        }

        try {
            return pointService.charge(idempotencyKey, request);
        } finally {
            // unlock() 실패가 이미 완성된 성공 응답을 덮어쓰지 않도록 방어한다.
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("포인트 충전 락 해제에 실패했습니다. leaseTime 이후 자동 만료됩니다. userId={}",
                            request.userId(), e);
                }
            }
        }
    }
}
