package com.example.coffeeordersystem.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "idempotency_key")
@Getter
@NoArgsConstructor
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(name = "endpoint", nullable = false, length = 50)
    private String endpoint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public IdempotencyKey(String idempotencyKey, String endpoint, LocalDateTime createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.endpoint = endpoint;
        this.createdAt = createdAt;
    }
}
