package com.example.coffeeordersystem.order.dto;

import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.order.entity.Order;

import java.time.LocalDateTime;


public record OrderResponse(
        Long orderId,
        Long userId,
        Long menuId,
        String menuName,
        Integer paidAmount,
        String status,
        LocalDateTime orderedAt
) {

    public static OrderResponse of(Order order, Menu menu) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getMenuId(),
                menu.getName(),
                order.getPaidAmount(),
                order.getStatus().name(),
                order.getOrderedAt()
        );
    }
}
