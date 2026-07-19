# ADR 운영 규칙

## 상태 표기

ADR은 `Proposed`, `Accepted`, `Rejected`, `Deprecated`, `Superseded` 중 하나의 상태와 결정일을 기록한다.

## 기존 결정 대체

기존 결정을 조용히 덮어쓰지 않는다. 결정이 바뀌면 새 ADR을 만들고, 새 문서에서 대체 대상 ADR을, 기존 문서에서 새 ADR을 서로 링크한다. 기존 문서는 `Superseded` 상태로 남겨 당시 결정과 변경 이유를 추적할 수 있게 한다.

## 검증 표기

실제로 실행해 관찰한 검증은 "실제 근거"로, 아직 구현·실행하지 않은 항목은 "계획된 검증"으로 분리해서 기록한다. 계획을 통과 결과나 현재 동작 근거로 표현하지 않는다.

## 목록

- [ADR-001 도메인 패키지 + 3계층 구조](ADR-001-layered-architecture.md)
- [ADR-002 Redisson과 DB 비관적 락](ADR-002-redisson-and-db-pessimistic-lock.md)
- [ADR-003 이벤트 발행에 Kafka 사용](ADR-003-kafka-for-event-publishing.md)
- [ADR-004 Redis ZSET 인기 메뉴 랭킹](ADR-004-redis-zset-ranking.md)
- [ADR-005 포인트 원장(ledger) 테이블 도입](ADR-005-point-ledger-table.md)
- [ADR-006 Idempotency Key 도입](ADR-006-idempotency-key.md)
- [ADR-007 Testcontainers와 k6 테스트 전략](ADR-007-testcontainers-and-k6.md)
