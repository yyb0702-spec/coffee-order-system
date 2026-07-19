package com.example.coffeeordersystem.order.service;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.exception.ErrorCode;
import com.example.coffeeordersystem.order.dto.OrderRequest;
import com.example.coffeeordersystem.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
  주문 파사드. docs/order-policy.md의 "동시성 제어" 1번(Redisson 분산 락)을 담당한다.
  userId 기준으로 요청 진입을 직렬화해 트래픽/중복요청을 앞단에서 거르고,
  최종 정합성은 OrderPaymentProcessor의 DB 비관적 락이 보장한다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final String LOCK_KEY_PREFIX = "order-lock:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;

    private final RedissonClient redissonClient;
    private final OrderPaymentProcessor orderPaymentProcessor;

    public OrderResponse placeOrder(String idempotencyKey, OrderRequest request) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + request.userId());

        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.ORDER_LOCK_NOT_ACQUIRED);
        }

        if (!acquired) {
            throw new BusinessException(ErrorCode.ORDER_LOCK_NOT_ACQUIRED);
        }

        try {
            return orderPaymentProcessor.pay(idempotencyKey, request);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
