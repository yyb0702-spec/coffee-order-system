# ADR-003 이벤트 발행에 Kafka 사용

## 상태와 결정일

Accepted. 결정일: 2026-07-19.

## 맥락과 문제

주문 완료 후 (1) 데이터 수집 플랫폼으로 주문 내역을 실시간 전송하고 (2) 인기 메뉴 랭킹용 파생 데이터를 갱신해야 한다. 두 작업 모두 결제 트랜잭션의 안정성을 해치지 않아야 하며, 다수 서버·다수 인스턴스 환경에서 전달이 보장돼야 한다.

## 결정 동인

- 외부 호출 실패가 결제 성공 여부에 영향을 주지 않아야 한다.
- 다수 인스턴스에서 이벤트 발행·소비가 안전하게 동작해야 한다(순서, 중복 방지).
- 실패한 처리를 추적·재시도할 수 있어야 한다.

## 검토한 선택지

| 선택지 | 장애 동작 | 정합성 영향 | 운영 위험 | 판단 |
| --- | --- | --- | --- | --- |
| 결제 트랜잭션 내부 동기 호출 | 외부 API 장애·지연이 곧바로 결제 실패로 전파된다. | 강한 일관성이지만 결제 안정성과 상충한다. | 외부 시스템 장애가 핵심 기능을 마비시킬 수 있다. | 제외. |
| `AFTER_COMMIT` 비동기 이벤트만 사용(Kafka 없이) | 별도 인프라는 불필요하지만, 인스턴스 재시작 시 이벤트가 유실될 수 있다. | 다수 인스턴스에서의 전달 보장이 약하다. | 재시도·DLT 같은 표준화된 장치가 없다. | 제외. 다수 인스턴스 요구사항과 재처리 가능성을 충분히 충족하지 못한다. |
| Kafka 발행 + 별도 Consumer | 소비 실패는 재시도 후 DLT로 이동한다. | 주문 DB와 파생 데이터(랭킹, 외부 전송)는 비동기적이며 일시적 지연이 가능하다. Consumer 멱등성이 필요하다. | topic retention, consumer lag, DLT 누적 관리가 필요하다. | 채택. |

## 결정과 이유

주문 결제 트랜잭션 커밋 후(`AFTER_COMMIT`) `OrderEventProducer`가 `OrderCompletedEvent`를 Kafka(topic `order.completed`)로 발행한다. 메시지 key는 `userId`로 둬 같은 사용자 이벤트의 파티션 순서를 보장한다. `RankingEventConsumer`가 이를 소비해 Redis ZSET을 갱신하고, 별도 Consumer가 데이터 수집 플랫폼으로의 전달을 담당한다(스켈레톤 단계에서는 랭킹 소비자만 구현). Consumer 에러 핸들러는 `FixedBackOff(1000ms, 2회)` 뒤 `DeadLetterPublishingRecoverer`로 DLT(`order.completed.DLT`)에 이동한다.

## 결과와 단점

주문 처리와 랭킹 갱신·외부 전송을 분리하고 실패 메시지를 DLT로 격리할 수 있다. 반면 주문 성공 직후 랭킹이 즉시 반영된다는 보장은 없으며, `eventId` 기반 멱등 처리와 topic·consumer 운영이 추가로 필요하다. DLT에 쌓인 메시지의 자동 재처리는 이번 스코프에서 의도적으로 제외했다(ADR-004, `docs/popular-menu-policy.md`의 복구 전제조건 참고).

## 검증 현황과 계획

- 실제 근거: 없음 (스켈레톤 단계, Kafka 인프라 미기동).
- 계획된 검증: 성공 이벤트의 ZSET 반영, 재시도 2회 뒤 DLT 이동, 같은 `eventId` 중복 소비 시 단일 반영을 Testcontainers Kafka로 검증한다.

## 재검토 조건

- 이벤트 계약, topic retention, 또는 DLT 재처리 정책이 바뀔 때.
- 메시지 지연이나 운영 복잡도가 과제 요구를 넘어설 때.

## 관련 항목

- 설계: `docs/order-policy.md`, `docs/design-rationale.md`.
- 구현: `event.producer.OrderEventProducer`, `event.consumer.RankingEventConsumer`, `common.config.KafkaProducerConfig`.
- 대체·폐기 규칙: [ADR 운영 규칙](adr-readme.md)을 따른다.
