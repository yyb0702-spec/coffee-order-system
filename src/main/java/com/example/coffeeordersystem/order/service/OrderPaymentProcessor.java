package com.example.coffeeordersystem.order.service;

import com.example.coffeeordersystem.common.exception.BusinessException;
import com.example.coffeeordersystem.common.exception.ErrorCode;
import com.example.coffeeordersystem.event.dto.OrderCompletedEvent;
import com.example.coffeeordersystem.menu.entity.Menu;
import com.example.coffeeordersystem.menu.repository.MenuRepository;
import com.example.coffeeordersystem.order.dto.OrderRequest;
import com.example.coffeeordersystem.order.dto.OrderResponse;
import com.example.coffeeordersystem.order.entity.Order;
import com.example.coffeeordersystem.order.entity.OrderStatus;
import com.example.coffeeordersystem.order.repository.OrderRepository;
import com.example.coffeeordersystem.point.entity.PointTransaction;
import com.example.coffeeordersystem.point.entity.PointTransactionType;
import com.example.coffeeordersystem.point.entity.UserPoint;
import com.example.coffeeordersystem.point.repository.PointTransactionRepository;
import com.example.coffeeordersystem.point.repository.UserPointRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderPaymentProcessor {

    private final MenuRepository menuRepository;
    private final UserPointRepository userPointRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public OrderResponse pay(OrderRequest request) {
        Menu menu = menuRepository.findById(request.menuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));

        UserPoint userPoint = userPointRepository.findByUserIdForUpdate(request.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_POINT_NOT_FOUND));

        if (userPoint.getBalance() < menu.getPrice()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_POINT);
        }

        userPoint.use(menu.getPrice());

        pointTransactionRepository.save(new PointTransaction(
                request.userId(),
                PointTransactionType.USE,
                menu.getPrice(),
                userPoint.getBalance(),
                LocalDateTime.now()
        ));

        Order order = orderRepository.save(new Order(
                request.userId(),
                menu.getId(),
                menu.getPrice(),
                OrderStatus.PAID,
                LocalDateTime.now()
        ));

        // AFTER_COMMIT 시점에 Kafka로 발행하기 위해 Spring ApplicationEvent로만 발행한다.
        // 실제 Kafka 전송은 event.producer.OrderEventProducer가 담당한다 (docs/order-policy.md).
        eventPublisher.publishEvent(OrderCompletedEvent.of(order, menu));

        return OrderResponse.of(order, menu);
    }
}
