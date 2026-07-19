# 인기 메뉴 정책

## 기본 규칙

- 최근 7일 기준은 `orders.ordered_at`이며 오늘을 포함한다.
- 상위 3개(top3) 메뉴를 조회한다.
- 동점 처리 기준: 주문 수 내림차순, 메뉴 ID 오름차순.

## 데이터 원천

- DB(`orders`)가 원천 데이터, Redis ZSET은 파생 데이터다.
- 요청 경로(동기)에서 ZSET을 직접 갱신하지 않는다. `ranking-consumer-group`이 `OrderCompletedEvent`(Kafka, topic `order.completed`)를 소비해 ZSET을 갱신한다. DB와의 이중쓰기 불일치를 피하기 위함이다.
- ZSET이 유실되거나 값이 어긋나면 DB 집계 쿼리로 재계산해 복구할 수 있어야 한다. 자동 복구 파이프라인(Kafka replay 기반) 구현은 이번 과제 범위 밖으로 명시하고, 향후 확장 과제로 분리한다.

## Redis Key 설계

일자별로 키를 분리해 "최근 7일" 슬라이딩 윈도우를 표현한다.

```text
popular:menus:2026-07-09
popular:menus:2026-07-08
...
```

- 쓰기: `ZINCRBY popular:menus:{date} 1 {menuId}` (주문 완료 이벤트를 소비할 때마다 해당 날짜 키에 반영)
- 읽기: 최근 7일치 일별 키를 선택 → 점수를 임시 키로 합산(`ZUNIONSTORE` 등) → 합산 점수 내림차순으로 