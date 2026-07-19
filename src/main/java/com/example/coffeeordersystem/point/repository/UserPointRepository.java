package com.example.coffeeordersystem.point.repository;

import com.example.coffeeordersystem.point.entity.UserPoint;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserPointRepository extends JpaRepository<UserPoint, Long> {

    Optional<UserPoint> findByUserId(Long userId);

    /**
     * 주문/충전 처리 시 사용하는 비관적 쓰기 락 조회 (SELECT ... FOR UPDATE).
     * docs/order-policy.md의 동시성 제어 참고.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select up from UserPoint up where up.userId = :userId")
    Optional<UserPoint> findByUserIdForUpdate(@Param("userId") Long userId);
}
