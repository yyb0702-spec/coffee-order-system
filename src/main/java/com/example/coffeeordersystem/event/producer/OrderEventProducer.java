package com.example.coffeeordersystem.event.producer;

import com.example.coffeeordersystem.event.dto.OrderCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;


@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;

    @Value("${app.kafka.topic.order-completed}")
    private String orderCompletedTopic;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(OrderCompletedEvent event) {
        kafkaTemplate.send(orderCompletedTopic, String.valueOf(event.userId()), event);
    }
}
