package com.example.coffeeordersystem.event.admin;

import com.example.coffeeordersystem.event.dto.OrderCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ADR-003 재검토 조건: DLT(order.completed.DLT)에 격리된 메시지를 원본 topic으로
 * 되돌려 재처리하는 운영 도구 (#82). 자동 재처리는 스코프 밖으로 남기고,
 * 운영자가 원인을 확인한 뒤 수동으로 트리거하는 것을 전제로 한다.
 * <p>
 * RankingEventConsumer가 eventId 기준 멱등 처리(ProcessedEvent unique 제약)를 하므로,
 * 이미 처리된 이벤트가 재발행되어도 두 번 반영되지 않는다.
 */
@Service
@Slf4j
public class DltReplayService {

    private static final String REPLAY_CONSUMER_GROUP = "dlt-replay-tool";

    private final String bootstrapServers;
    private final String dltTopic;
    private final String originalTopic;
    private final KafkaTemplate<String, OrderCompletedEvent> orderCompletedKafkaTemplate;

    public DltReplayService(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${app.kafka.topic.order-completed-dlt}") String dltTopic,
            @Value("${app.kafka.topic.order-completed}") String originalTopic,
            KafkaTemplate<String, OrderCompletedEvent> orderCompletedKafkaTemplate) {
        this.bootstrapServers = bootstrapServers;
        this.dltTopic = dltTopic;
        this.originalTopic = originalTopic;
        this.orderCompletedKafkaTemplate = orderCompletedKafkaTemplate;
    }

    /**
     * DLT에서 최대 maxRecords건을 읽어 원본 topic으로 재발행하고, 성공적으로 재발행한
     * 레코드만큼 DLT 쪽 컨슈머 오프셋을 커밋한다.
     *
     * @return 재발행한 건수
     */
    public int replay(int maxRecords) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, REPLAY_CONSUMER_GROUP,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        );

        // 헤더의 __TypeId__ 대신 명시적으로 OrderCompletedEvent로 역직렬화한다.
        // (DLT는 실패한 레코드의 원본 타입을 그대로 담아 재발행하므로 target type이 고정적이다)
        try (KafkaConsumer<String, OrderCompletedEvent> consumer = new KafkaConsumer<>(
                props,
                new StringDeserializer(),
                new JsonDeserializer<>(OrderCompletedEvent.class, false))) {

            consumer.subscribe(List.of(dltTopic));

            int replayed = 0;
            while (replayed < maxRecords) {
                ConsumerRecords<String, OrderCompletedEvent> records = consumer.poll(Duration.ofSeconds(3));
                if (records.isEmpty()) {
                    break;
                }

                for (ConsumerRecord<String, OrderCompletedEvent> record : records) {
                    if (replayed >= maxRecords) {
                        break;
                    }
                    orderCompletedKafkaTemplate.send(originalTopic, record.key(), record.value());
                    replayed++;
                }
                consumer.commitSync();
            }

            log.info("DLT 재발행 완료: {}건 ({} -> {})", replayed, dltTopic, originalTopic);
            return replayed;
        }
    }
}
