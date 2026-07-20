# 랭킹 Redis 이중 카운트 — 서로 다른 두 저장소를 하나의 트랜잭션처럼 다룰 수 없다

## 문제 상황

`RankingEventConsumer`는 Kafka의 `order.completed` topic을 구독해 이벤트 하나를 처리할 때마다
두 가지 작업을 한다.

1. 처리 이력을 DB에 남긴다 (`ProcessedEvent` insert — eventId 기준 멱등 처리용 ledger).
2. 그날의 인기 메뉴 랭킹을 Redis ZSET에 반영한다 (`ZINCRBY`로 점수 +1).

처음 구현했을 때는 이 순서가 반대였다 — Redis 반영을 먼저 하고, DB insert를 나중에 했다.

```java
// 처음 구현 (문제가 있던 순서)
redisTemplate.opsForZSet().incrementScore(dateKey, String.valueOf(event.menuId()), 1);
processedEventRepository.save(new ProcessedEvent(event.eventId(), ...));
```

컨슈머 메서드에는 `@Transactional`이 붙어 있어서, 얼핏 보면 이 두 작업이 하나의 트랜잭션으로
묶여 안전할 것처럼 보인다.

## 원인

`@Transactional`은 JPA/Hibernate가 관리하는 DB 작업만 롤백 대상으로 삼는다. `StringRedisTemplate`을
통한 Redis `ZINCRBY` 호출은 이 트랜잭션과 완전히 무관하다 — Redis 쪽에서 보면 그 연산은 호출 즉시
실행되고 즉시 확정되는, 트랜잭션이라는 개념 자체가 없는 별도의 저장소 작업이다.

이 상태에서 다음 순서로 장애가 나면 이중 카운트가 발생한다.

1. 이벤트 A가 도착한다.
2. Redis 점수가 +1 된다 (성공, 이미 확정됨).
3. `ProcessedEvent` DB insert가 실패한다 (예: 순간적인 커넥션 문제, 제약 위반 등).
4. `@Transactional`이 예외를 감지해 트랜잭션을 롤백한다 — 하지만 롤백되는 건 DB 쪽뿐이고,
   이미 확정된 Redis 점수는 그대로 남는다.
5. Kafka 리스너의 에러 핸들러(`DefaultErrorHandler` + `FixedBackOff`)가 같은 이벤트를 재시도한다.
6. 재시도가 성공하면, Redis 점수가 **또** +1 된다. 원래 1번만 올라가야 할 점수가 2번 올라간
   상태로 확정된다.

DB는 "이 이벤트를 아직 처리 안 한 것"으로 보고 재시도하는데, Redis는 이미 한 번 반영된 상태라서
생기는 불일치다. 두 저장소의 "실패했을 때 되돌릴 수 있는지"가 서로 다르다는 걸 순서 설계에
반영하지 않은 게 근본 원인이었다.

## 해결

DB insert를 Redis 반영보다 먼저 수행하도록 순서를 바꿨다.

```java
// src/main/java/.../event/consumer/RankingEventConsumer.java
@KafkaListener(topics = "${app.kafka.topic.order-completed}", groupId = CONSUMER_GROUP)
@Transactional
public void consume(OrderCompletedEvent event) {
    if (processedEventRepository.existsByEventId(event.eventId())) {
        log.info("이미 처리된 이벤트입니다. eventId={}", event.eventId());
        return;
    }

    // DB insert(원장/ledger)를 Redis 반영보다 먼저 수행한다.
    processedEventRepository.save(new ProcessedEvent(
            event.eventId(), "OrderCompletedEvent", CONSUMER_GROUP, LocalDateTime.now()
    ));

    String dateKey = KEY_PREFIX + event.orderedAt().toLocalDate().format(DATE_FORMAT);
    redisTemplate.opsForZSet().incrementScore(dateKey, String.valueOf(event.menuId()), 1);
}
```

이 순서가 안전한 이유는 두 가지 실패 시나리오를 모두 커버하기 때문이다.

- **DB insert가 실패하는 경우**: Redis는 아직 손대지 않은 상태다. 트랜잭션이 롤백되고, 재시도는
  깨끗한 상태에서 다시 시작한다. 이중 카운트 여지가 없다.
- **DB insert는 성공했는데 Redis 반영이 실패하는 경우**: `redisTemplate` 호출에서 던져진 예외가
  `@Transactional` 메서드 밖으로 전파되므로, 방금 성공한 DB insert까지 함께 롤백된다. 다음 재시도는
  `existsByEventId` 체크를 다시 통과하고, DB insert와 Redis 반영을 처음부터 함께 재현한다.

즉 "먼저 실패해도 안전한 쪽(DB, 트랜잭션 롤백 가능)"을 앞에 두고, "실패해도 앞 단계를 되돌릴 수
있는 쪽(Redis 실패 시 DB도 같이 롤백됨)"을 뒤에 두는 순서로 재설계한 것이다.

## 검증

`OrderEventKafkaIntegrationTest`(Testcontainers Kafka)에 같은 `eventId`를 두 번 발행했을 때
`ProcessedEvent`가 중복 저장되지 않고 Redis 점수도 한 번만 반영되는지 확인하는 테스트를 포함시켰다.
`./gradlew test`로 로컬에서 실제 통과를 확인했다.

## 배운 점

여러 저장소에 걸친 작업을 하나의 논리적 단위로 다뤄야 할 때, "트랜잭션이 걸려 있으니 안전하다"는
가정은 그 트랜잭션이 실제로 감싸는 범위가 어디까지인지 확인하지 않으면 틀릴 수 있다. 완전한
원자성이 필요하다면 Outbox 패턴처럼 더 견고한 방식을 검토해야 하고(이 프로젝트의 향후 확장
과제로 남겨둔 부분이다), 그게 과하다면 최소한 "실패해도 되돌릴 수 있는 작업을 앞에, 되돌릴 수
없는 작업을 뒤에" 두는 순서 설계만으로도 이중 처리 같은 흔한 사고는 막을 수 있다.
