package com.example.coffeeordersystem.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자별 포인트 잔액. docs/db-schema.sql의 user_point 테이블에 대응.
 * balance는 point_transaction 합계를 반영하는 캐시값이며, 원천은 point_transaction이다
 * (docs/point-policy.md).
 *
 * 동시 차감 시 정합성은 이 엔티티를 비관적 락(SELECT FOR UPDATE)으로 조회한 뒤 보장한다.
 * 락 획득/조회는 UserPointRepository에서 담당한다.
 */
@Entity
@Table(name = "user_point")
@Getter
@NoArgsConstructor
public class UserPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "balance", nullable = false)
    private Integer balance;

    public UserPoint(Long userId, Integer balance) {
        this.userId = userId;
        this.balance = balance;
    }

    /**
     * TODO: 충전 시 balance를 증가시킨다. 도메인 검증(금액 범위)은 PointService에서 먼저 수행한다.
     */
    public void charge(int amount) {
        this.balance += amount;
    }

    /**
     * TODO: 주문 결제 시 balance를 차감한다. 잔액 부족 시 BusinessException(INSUFFICIENT_POINT).
     */
    public void use(int amount) {
        if (this.balance < amount) {
            throw new IllegalStateException("잔액이 부족합니다.");
        }
        this.balance -= amount;
    }
}
