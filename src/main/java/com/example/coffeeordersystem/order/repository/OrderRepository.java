package com.example.coffeeordersystem.order.repository;

import com.example.coffeeordersystem.order.entity.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("select o.menuId as menuId, count(o) as orderCount from Order o " +
            "where o.status = com.example.coffeeordersystem.order.entity.OrderStatus.PAID " +
            "and o.orderedAt >= :from and o.orderedAt < :to " +
            "group by o.menuId order by count(o) desc, o.menuId asc")
    List<MenuOrderCount> findTopPaidMenuOrderCounts(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable
    );
}
