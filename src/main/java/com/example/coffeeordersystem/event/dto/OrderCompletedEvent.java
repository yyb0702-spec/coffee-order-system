package com.example.coffeeordersystem.event.dto;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.order.entity.Order;

import java.time.LocalDateTime;
import java.util.UUID;


public record OrderCompletedEvent(
        String eventId,
        Long orderId,
        Long userId,
        Long menuId,
        Integer paidAmount,
        LocalDateTime orderedAt
) {

    public static OrderCompletedEvent of(Order order, Menu menu) {
        return new OrderCompletedEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                order.getUserId(),
                menu.getId(),
                order.getPaidAmount(),
                order.getOrderedAt()
        );
    }
}
