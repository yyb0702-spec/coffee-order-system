package com.example.coffeeordersystem.common.config;

import com.example.coffeeordersystem.event.dto.OrderCompletedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * OrderCompletedEvent 전용 KafkaTemplate. 제네릭 타입을 명시적으로 고정해
 * Boot 기본 자동구성 KafkaTemplate<Object, Object>와의 타입 불일치 위험을 피한다.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, OrderCompletedEvent> orderCompletedProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        );
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, OrderCompletedEvent> orderCompletedKafkaTemplate(
            ProducerFactory<String, OrderCompletedEvent> orderCompletedProducerFactory) {
        return new KafkaTemplate<>(orderCompletedProducerFactory);
    }
}
