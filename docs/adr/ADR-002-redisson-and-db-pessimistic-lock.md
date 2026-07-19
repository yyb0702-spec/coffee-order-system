# ADR-002 Redisson과 DB 비관적 락

## 상태와 결정일

Accepted. 결정일: 2026-07-19. 튜터 확인은 여전히 권장한다.

## 맥락과 문제

다수 서버·다수 인스턴스 환경에서 같은 사용자의 주문이 동시에 처리되면 같은 잔액을 읽고 중복 차감할 수 있다. `docs/order-policy.md`의 동시성 테스트 시나리오(초기 10,000P, 메뉴 4,000P, 동시 주문 10건 → 기대 성공 2건·최종 잔액 2,000P)는 이 위험을 구체화한다.

## 결정 동인

- 다수 인스턴스에서 같은 `userId`의 동시 주문 진입 자체를 줄인다.
- Redis 락의 장애·타임아웃과 무관하게 DB 잔액의 최종 정합성을 보장한다.
- 주문 로직이 "잔액 확인 → 차감 → 원장 기록 → 주문 저장"의 여러 단계로 구성돼 있어, 단일 원자적 UPDATE 한 줄로는 전체 임계구역을 보장할 수 없다.

## 검토한 선택지

| 선택지 | 장애 동작 | 정합성 영향 | 운영 위험 | 판단 |
| --- | --- | --- | --- | --- |
| 원자적 UPDATE 단독 | 락 자체가 없어 장애 지점이 없다. | 단일 쿼리 범위만 원자적이라, 원장 기록·주문 저장까지 묶지 못한다. | 구현은 가장 단순하다. | 제외. 이번 주문 로직의 다단계 흐름을 못 덮는다. |
| DB 비관적 락만 사용 | Redis 장애 영향은 없지만 모든 동시 요청이 DB 경합으로 간다. | `user_point` row 잠금으로 음수 잔액을 막는다. | 다수 인스턴스에서 고경합 시 DB 부하·지연 증가. | 보류. 정확성은 충분하지만 트래픽 방어가 없다. |
| Redisson만 사용 | Redis 장애·TTL 만료 시 보호가 약화될 수 있다. | DB 최종 정합성만으로는 보장되지 않는다. | 락 키·TTL 설정 오류가 잔액 오류로 이어질 수 있다. | 제외. 단일 락을 최종 정합성 근거로 둘 수 없다. |
| Redisson + DB 비관적 락 | Redis 락 획득 실패·만료가 있어도 DB 락이 최종 방어선이다. | `user_point` row 잠금으로 음수 잔액을 막는다. | Redis 장애 시 DB 경합 증가, 락 TTL·대기 설정 오판 위험. | 채택. |

## 결정과 이유

`OrderService`가 먼저 Redisson 분산 락(`order-lock:{userId}`)으로 주문 진입을 직렬화해 트래픽/중복 요청을 앞단에서 거르고, 락을 얻은 요청만 `OrderPaymentProcessor`의 DB 트랜잭션(`SELECT ... FOR UPDATE`)으로 들어간다. 정확성의 최종 책임은 DB 락이 지고, Redisson은 트래픽 방어용 필터라는 역할 분리를 유지한다.

Redisson 락 획득에 실패(타임아웃)하면 DB 트랜잭션까지 진입하지 않고 즉시 `ORDER_LOCK_NOT_ACQUIRED`(409)로 응답한다.

## 결과와 단점

동시성 실패의 책임을 진입 제어(Redisson)와 최종 저장(DB 락)으로 나누어 설명할 수 있다. 반면 락 획득 순서, TTL, 대기 시간, Redis 가용성을 함께 운영해야 하고 두 잠금의 대기 시간이 합쳐질 수 있다. Redis 장애 시 DB 락 단독 경로로 폴백할지, 즉시 거절할지는 별도 판단이 필요하며 현재는 즉시 거절(fail-closed)로 구현돼 있다.

## 검증 현황과 계획

- 실제 근거: 없음 (스켈레톤 단계, 로컬 빌드·실행 미검증).
- 계획된 검증: 동일 사용자 10개 동시 주문 요청에서 성공 2건, 최종 잔액 2,000P, 음수 잔액 없음을 `ExecutorService` + `CountDownLatch` 기반 통합 테스트(Testcontainers MySQL)로 확인한다. 다수 인스턴스 환경 재현은 docker-compose로 앱 컨테이너 2개 이상을 띄운 뒤 k6로 별도 검증한다.

## 재검토 조건

- 실제 부하에서 DB 경합 또는 Redisson 대기가 허용 기준을 넘을 때.
- Redis 장애 시 fail-closed 대신 DB 락 단독 폴백으로 정책을 바꿀 근거가 생길 때.

## 관련 항목

- 설계: `docs/order-policy.md`, `docs/design-rationale.md`.
- 구현: `order.service.OrderService`, `order.service.OrderPaymentProcessor`, `point.repository.UserPointRepository#findByUserIdForUpdate`.
- 대체·폐기 규칙: [ADR 운영 규칙](adr-readme.md)을 따른다.
