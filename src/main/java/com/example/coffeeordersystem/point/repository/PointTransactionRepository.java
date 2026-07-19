package com.example.coffeeordersystem.point.repository;

import com.example.coffeeordersystem.point.entity.PointTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTransactionRepository extends JpaRepository<PointTransaction, Long> {
}
