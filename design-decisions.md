# 커피 주문 시스템 - 최종 설계 결정

Q&A를 통해 확정한 설계 결정 사항. README.md의 "설계 내용 / 설계 의도 / 문제해결 전략 / 기술적 선택 이유" 섹션 작성 시 이 문서를 기반으로 옮겨 쓰면 됩니다.

---

## 결정 요약

| 영역 | 결정 | 한 줄 이유 |
| --- | --- | --- |
| 포인트 이력 | 원장 테이블(`point_transaction`) 추가 | 충전/차감 내역 감사·추적 가능하도록 |
| 동시성 제어 | 비관적 락(`SELECT FOR UPDATE`) + Redisson 병행 | DB 락이 정합성 최종 보장, Redisson은 동일 유저 중복요청/트래픽을 앞단에서 차단 |
| 외부 전송 | Kafka 발행 (`OrderCompletedEvent`) | 다중 인스턴스 환경에서 순서·전달 보장, 전송 실패가 결제 흐름과 완전히 분리 |
| 인기 메뉴 집계 | DB(원천) + Redis ZSET(파생) 병행 | Kafka consumer가 ZSET 갱신, DB는 언제든 재계산 가능한 원천 데이터로 유지 |
| 테스트 DB | Testcontainers(MySQL) + k6 부하테스트 | 락 동작을 실제 DB로 검증, k6로 동시 요청 상황에서의 시스템 동작까지 관찰 |
| 에러 처리 | 커스텀 예외 + 공통 에러코드(`@ControllerAdvice`) | 클라이언트가 에러 종류를 코드로 구분 가능 |
| 주문 상태 | `PAID` / `CANCELLED` 등 다중 상태 | 향후 환불·취소 확장을 고려한 구조 |
| 사용자 식별 | 충전 시 `user_point` row 자동 생성(upsert) | 별도 회원가입 없이 과제 범위에 맞게 단순화 |
| 충전 한도 | 1P ~ 1,000,000P | 비정상적 금액 입력/오버플로우 방지 |
| 중복요청 방지 | Idempotency Key 도입 | 네트워크 재시도로 인한 이중 주문/이중 충전 차단 |
| Kafka 실패 정책 | DLT 이동만, 자동 재처리 API는 범위 밖 | 메시지 유실은 막되, 재처리 자동화는 과제 스코프 밖으로 명시적으로 제외 |

---

## 1. 포인트 이력 - 원장 테이블

`point_transaction` 테이블을 추가해 충전(CHARGE)/사용(USE) 내역을 기록합니다. `user_point.balance`는 조회 편의를 위한 캐시값으로 유지하되, 정합성 검증 시 `point_transaction` 합계와 대조 가능하게 설계합니다.

- `point_transaction`: id, user_id, type(CHARGE/USE), amount, balance_after, created_at
- 주문/결제 시 `point_transaction`(USE) insert + `user_point.balance` 차감을 같은 트랜잭션에서 처리

## 2. 동시성 제어 - 비관적 락 + Redisson

- 주문 요청이 들어오면 Redisson 분산 락으로 `userId` 기준 진입을 우선 직렬화 (동일 유저의 동시 요청이 여러 개 들어와도 하나씩만 처리 흐름에 진입)
- DB 트랜잭션 내에서 `user_point` row를 `SELECT FOR UPDATE`로 잠그고 잔액 확인 → 차감 → `point_transaction` insert → `orders` insert까지 하나의 임계구역으로 처리
- 두 장치의 역할이 다름: Redisson은 트래픽/중복 차단용 필터, DB 락은 최종 정합성 보장 장치. Redisson 없이 DB 락만 있어도 정확성은 보장되지만, Redisson이 앞단에서 걸러주면 DB 락 대기가 줄어 처리량에 유리

## 3. 외부 전송 - Kafka

- 결제 성공(커밋 후) → `OrderCompletedEvent`(eventId, orderId, userId, menuId, paidAmount, orderedAt) 발행
- 별도 Consumer가 데이터 수집 플랫폼(Mock API)으로 전달 담당 → 전송 실패가 결제 성공 여부에 영향 없음
- 이벤트 발행 자체가 유실되는 것을 막기 위해, 최소한 `AFTER_COMMIT` 시점에 발행하거나 Outbox 테이블을 경유하는 방식을 구현 단계에서 검토

## 4. 인기 메뉴 집계 - DB + Redis ZSET

- DB `orders` 테이블이 원천 데이터, Redis ZSET은 파생 데이터라는 원칙을 명확히 함
- `ranking-consumer-group`이 `OrderCompletedEvent`를 소비해 ZSET 갱신 (요청 경로에서 직접 갱신하지 않음 → 이중쓰기 문제 회피)
- ZSET 유실/불일치 시 DB 재계산으로 복구 가능하도록 설계 (자동 복구 자동화는 이번 스코프에서는 제외 가능, README에 명시)
- 동점 처리 기준: 주문 수 내림차순, 메뉴 ID 오름차순 (참고 저장소와 동일하게 채택 권장)

## 5. 테스트 전략

- 동시성 테스트: `ExecutorService` + `CountDownLatch`로 동일 유저 동시 주문 N개 발생시켜 잔액 음수 방지 검증 (필수)
- 통합 테스트: Testcontainers로 실제 MySQL 기동, 락 동작 검증
- k6: 동시 주문/충전 요청에 대한 부하 테스트로 도전 요구사항(동시성) 근거 자료 확보
- 외부 전송 실패 시나리오: Mock 서버로 실패 케이스 구성, "전송 실패해도 주문은 성공 유지" 검증

## 6. 에러 처리

- 커스텀 예외 계층 + `@ControllerAdvice`로 통일된 응답 `{errorCode, message}`
- 매핑 예시: 잔액부족 409, 메뉴/유저 없음 404, 유효성 실패 400, 중복요청(Idempotency Key 충돌) 409

## 7. 주문 상태

- `PAID`, `CANCELLED` 등 다중 상태로 설계 (이번 과제에서 취소 API까지 구현할지는 별도 결정, 구조만 열어둠)

## 8. 사용자 식별

- 별도 회원가입 API 없이 `userId`를 그대로 받되, 포인트 충전 요청 시 `user_point` row가 없으면 자동 생성
- 주문/결제 요청 시 `user_point` row가 없으면 실패 처리 (충전 이력 없는 유저는 주문 불가)

## 9. 충전 한도

- 최소 1P, 1회 최대 1,000,000P
- 범위를 벗어나면 400 에러

## 10. 중복요청 방지 - Idempotency Key

- 클라이언트가 요청마다 고유 키(예: UUID)를 헤더로 전달
- 서버는 해당 키를 unique 제약이 걸린 테이블(또는 Redis SETNX)로 기록해 중복 요청을 차단
- 주문/충전 API 모두 적용 권장

## 11. Kafka 실패 정책 (DLT)

- 재시도 후에도 반복 실패하는 메시지는 DLT(Dead Letter Topic)로 이동
- 자동 재처리 API는 이번 과제 범위 밖으로 명시 (수동/스크립트 처리로 대체), README에 "향후 확장 과제"로 명시

---

## ERD 반영 사항 (갱신)

기존 4테이블(menu, user_point, orders, processed_event)에 아래를 추가/변경합니다.

- `point_transaction` 테이블 신규 추가 (1번 항목)
- `orders.status`를 `PAID` 단일값에서 enum(`PAID`, `CANCELLED` 등)으로 확장
- `idempotency_key` 컬럼 또는 별도 테이블 추가 (10번 항목, unique 제약)

---

## 아직 열려있는 세부 구현 항목

구현 단계에서 자연스럽게 결정되는 항목으로, 별도 논의 없이 진행해도 되는 것들입니다.

- HTTP 메서드/URL: `GET /menus`, `POST /points/charge`, `POST /orders`, `GET /menus/popular`
- 인덱스: `user_point(user_id)` unique, `orders(status, ordered_at)`, `orders(ordered_at, menu_id)`, `point_transaction(user_id, created_at)`
- 스키마 관리: Flyway로 마이그레이션 고정 (JPA ddl-auto 미사용) 권장
