# ADR-005 포인트 원장(ledger) 테이블 도입

## 상태와 결정일

Accepted. 결정일: 2026-07-19.

## 맥락과 문제

`user_point.balance` 컬럼만으로는 충전/사용 내역을 추적할 수 없고, 잔액이 정확한지 검증할 근거도 없다. 도전 요구사항에 "데이터 일관성을 고려하여 구현"이 명시돼 있다.

## 결정 동인

- 잔액 변동의 근거(누가 언제 얼마나 충전/사용했는지)를 추적할 수 있어야 한다.
- 잔액 컬럼과 실제 거래 이력이 어긋나지 않는지 검증 가능해야 한다.
- 과도한 설계를 피하고 MVP 스코프에 맞는 선까지만 확장한다.

## 검토한 선택지

| 선택지 | 감사·추적 | 구현 복잡도 | 판단 |
| --- | --- | --- | --- |
| `user_point.balance` 컬럼만 유지 | 불가능. 현재 값만 남고 과거 내역이 없다. | 가장 단순하다. | 제외. 정합성 검증 근거가 없다. |
| `point_transaction` 원장 테이블 추가 | 가능. 충전/사용 각각을 이력으로 남기고 `balance_after` 스냅샷도 함께 저장한다. | 트랜잭션마다 원장 insert가 추가된다. | 채택. |

## 결정과 이유

`point_transaction`(`user_id`, `type`(CHARGE/USE), `amount`, `balance_after`, `created_at`) 테이블을 추가한다. `user_point.balance`는 조회 편의를 위한 캐시값으로 유지하되, 실제 원천은 `point_transaction`이다. 충전과 주문 결제 모두 원장 insert와 `balance` 갱신을 하나의 트랜잭션으로 처리한다.

## 결과와 단점

잔액의 근거를 추적할 수 있고, `point_transaction` 합계와 `user_point.balance`를 대조해 정합성을 검증할 수 있다. 반면 모든 포인트 변동에 원장 insert가 추가되어 쓰기 비용이 늘고, 두 테이블(캐시와 원장)이 어긋나지 않도록 항상 같은 트랜잭션 안에서 갱신해야 한다는 제약이 생긴다.

## 검증 현황과 계획

- 실제 근거: 없음 (스켈레톤 단계).
- 계획된 검증: 다수 충전·주문 트랜잭션 실행 후 `SUM(point_transaction.amount)` 기반 계산값과 `user_point.balance`가 항상 일치하는지 확인하는 정합성 테스트를 작성한다.

## 재검토 조건

- 원장 테이블의 쓰기 비용이 실측상 문제가 될 때, 비동기 이력 기록(Kafka 기반)으로 전환할 근거가 생길 때.

## 관련 항목

- 설계: `docs/point-policy.md`, `docs/design-rationale.md`.
- 구현: `point.entity.PointTransaction`, `point.entity.UserPoint`, `point.service.PointService`, `order.service.OrderPaymentProcessor`.
- 대체·폐기 규칙: [ADR 운영 규칙](adr-readme.md)을 따른다.
