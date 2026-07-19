package com.example.coffeeordersystem.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 포인트 충전/사용 원장. docs/db-schema.sql의 point_transaction 테이블에 대응.
 * 감사·잔액 검증용 원천 데이터다 (docs/point-policy.md).
 */
@Entity
@Table(name = "point_transaction")
@Getter
@NoArgsConstructor
public class PointTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private PointTransactionType type;

    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "balance_after", nullable = false)
    private Integer balanceAfter;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public PointTransaction(Long userId, PointTransactionType type, Integer amount,
                             Integer balanceAfter, LocalDateTime createdAt) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }
}
