# 포인트 정책

## 기본 규칙

- 1원 = 1P.
- 1회 충전 최소 금액은 1P, 최대 금액은 1,000,000P다. 범위를 벗어나면 400 에러.
- 포인트 잔액은 음수가 될 수 없다.
- 충전 요청 시 `user_point` row가 없으면 생성한다.
- 주문/결제 요청 시 `user_point` row가 없으면 실패한다.

## 데이터 모델 - 원장 테이블

잔액 컬럼만으로 관리하지 않고, 충전/사용 내역을 남기는 원장 테이블을 둔다. 감사·이력 추적 및 잔액 정합성 검증(원장 합계 vs 캐시값 대조)을 위함이다.

- `user_point`: `id`, `user_id`(unique), `balance` — 조회 편의를 위한 캐시값
- `point_transaction`: `id`, `user_id`, `type`(`CHARGE` / `USE`), `amount`, `balance_after`, `created_at` — 실제 원천 데이터

## 트랜잭션 경계

- 충전: `point_transaction`(CHARGE) insert + `user_point.balance` 증가를 하나의 트랜잭션으로 처리.
- 주문/결제: `point_transaction`(USE) insert + `user_point.balance` 차감을 주문 생성과 하나의 트랜잭션으로 처리 (`order-policy.md`의 동시성 제어 참고).

## 인덱스

- `user_point(user_id)` unique
- `point_transaction(user_id, created_at)`
