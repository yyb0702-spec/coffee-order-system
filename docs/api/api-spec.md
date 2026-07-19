# API 명세

Base path는 `/api`.

성공 응답은 별도 envelope 없이 리소스 JSON을 바로 반환한다. 에러 응답만 공통 포맷 `{ code, message }`를 사용한다 (`domain-policy.md` 참고).

주문/충전처럼 상태를 바꾸는 API는 `Idempotency-Key` 헤더를 필수로 받는다 (`order-policy.md`의 중복요청 방지 참고).

---

## GET /api/menus

커피 메뉴 목록을 조회한다.

### 응답 (200)

```json
[
  {
    "id": 1,
    "name": "아메리카노",
    "price": 4500
  }
]
```

---

## POST /api/points/charge

포인트를 충전한다.

### 요청 헤더

| Header | 필수 | 설명 |
| --- | --- | --- |
| `Idempotency-Key` | Y | 요청 재시도 시 중복 충전을 막기 위한 고유 키(UUID 권장) |

### 요청 본문

```json
{
  "userId": 1,
  "amount": 10000
}
```

### 제약

- `userId`는 양수.
- `amount`는 1 이상, 1,000,000 이하 (`point-policy.md`의 충전 한도).
- `user_point` row가 없으면 새로 생성한다 (자동 upsert).
- 충전 시 `point_transaction`(type=`CHARGE`) 이력을 함께 남긴다.

### 응답 (200)

```json
{
  "userId": 1,
  "balance": 10000
}
```

### 에러 케이스

| 상황 | code |
| --- | --- |
| `amount` 범위 밖 | `INVALID_CHARGE_AMOUNT` |
| `Idempotency-Key` 중복 | `DUPLICATE_REQUEST` |

---

## POST /api/orders

메뉴를 주문하고 포인트로 결제한다.

### 요청 헤더

| Header | 필수 | 설명 |
| --- | --- | --- |
| `Idempotency-Key` | Y | 중복 주문 방지용 고유 키 |

### 요청 본문

```json
{
  "userId": 1,
  "menuId": 1
}
```

### 처리 기준

- 메뉴가 없으면 주문을 생성하지 않는다.
- `user_point` row가 없으면 주문을 생성하지 않는다.
- 잔액이 부족하면 주문을 생성하지 않는다.
- Redisson 분산 락(`userId` 기준)으로 동시 요청 진입을 직렬화하고, DB 비관적 락으로 `user_point`의 최종 정합성을 보장한다 (`order-policy.md`의 동시성 제어).
- 주문 저장, `point_transaction`(type=`USE`) 이력 기록, 포인트 차감은 하나의 DB 트랜잭션으로 처리한다.
- 결제 금액은 주문 시점 메뉴 가격을 스냅샷(`paidAmount`)으로 저장한다.
- 커밋 성공 후 `OrderCompletedEvent`를 Kafka(`order.completed`)로 발행한다.
- 외부 PG/결제대행사 연동 없이, 사전 충전된 포인트 차감만으로 결제를 처리한다.

### 응답 (201)

```json
{
  "orderId": 100,
  "userId": 1,
  "menuId": 1,
  "menuName": "아메리카노",
  "paidAmount": 4500,
  "status": "PAID",
  "orderedAt": "2026-07-14T10:00:00"
}
```

### 에러 케이스

| 상황 | code | HTTP |
| --- | --- | --- |
| 메뉴 없음 | `MENU_NOT_FOUND` | 404 |
| `user_point` row 없음 | `USER_POINT_NOT_FOUND` | 404 |
| 잔액 부족 | `INSUFFICIENT_POINT` | 409 |
| Redisson 락 획득 실패 | `ORDER_LOCK_NOT_ACQUIRED` | 409 |
| `Idempotency-Key` 중복 | `DUPLICATE_REQUEST` | 409 |

---

## GET /api/menus/popular

최근 7일 인기 메뉴 Top 3를 조회한다.

### 응답 (200)

```json
[
  {
    "rank": 1,
    "menuId": 1,
    "menuName": "아메리카노",
    "orderCount": 12
  }
]
```

### 기준

- 기본 조회 원천은 Redis ZSET (`popular:menus:{date}` 일자별 키 합산, `popular-menu-policy.md` 참고).
- Redis 값은 파생 데이터이므로 DB 원천과 일시적으로 다를 수 있다.
- 동점 처리: 주문 수 내림차순, 메뉴 ID 오름차순.

---

## 공통 에러 응답 예시

```json
{
  "code": "INSUFFICIENT_POINT",
  "message": "포인트 잔액이 부족합니다."
}
```

전체 에러 코드 표는 `domain-policy.md` 참고.

## 상태 코드 요약

| API | 성공 |
| --- | --- |
| GET /api/menus | 200 |
| POST /api/points/charge | 200 |
| POST /api/orders | 201 |
| GET /api/menus/popular | 200 |
