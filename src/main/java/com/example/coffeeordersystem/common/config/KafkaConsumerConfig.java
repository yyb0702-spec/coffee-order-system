package com.example.coffeeordersystem.common.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * ADR-003 이벤트 발행에 Kafka 사용: 소비 실패 시 FixedBackOff(1000ms, 2회) 재시도 후
 * DeadLetterPublishingRecoverer로 order.completed.DLT topic으로 이동시킨다.
 * (문서에만 남아 있던 정책을 실제 컨테이너 팩토리/에러 핸들러로 구현)
 */
@Configuration
public class KafkaConsumerConfig {

    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long RETRY_MAX_ATTEMPTS = 2L;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${app.kafka.topic.order-completed-dlt}")
    private String orderCompletedDltTopic;

    // DLT 발행 전용 KafkaTemplate. 원본 컨슈머가 실패한 레코드의 key/value를 그대로 실어 보낸다.
    @Bean
    public ProducerFactory<Object, Object> deadLetterProducerFactory() {
        Map<String, Object> configProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
        );
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate(
            ProducerFactory<Object, Object> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        // partition은 -1(미지정)로 둬 DLT topic의 파티션 수가 원본과 달라도 안전하게 발행되도록 한다.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, ex) -> new TopicPartition(orderCompletedDltTopic, -1)
        );
        FixedBackOff backOff = new FixedBackOff(RETRY_INTERVAL_MS, RETRY_MAX_ATTEMPTS);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    // 빈 이름을 "kafkaListenerContainerFactory"로 두면 @KafkaListener의 기본 팩토리로 자동 적용된다.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
