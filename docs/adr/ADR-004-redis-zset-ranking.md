# ADR-004 Redis ZSET 인기 메뉴 랭킹

## 상태와 결정일

Accepted. 결정일: 2026-07-19.

## 맥락과 문제

최근 7일 인기 메뉴 top3를 "정확하게" 조회해야 한다. 매 요청마다 DB 집계 쿼리를 돌리면 정확하지만 조회 트래픽이 몰릴 때 부담이 되고, 캐시에만 의존하면 요구사항의 "정확성"과 상충할 수 있다.

## 결정 동인

- 메뉴별 주문 횟수가 정확해야 한다는 요구사항을 지킨다.
- 조회 트래픽이 늘어도 응답이 느려지지 않아야 한다.
- 랭킹 데이터가 유실되거나 어긋나도 복구 가능해야 한다.

## 검토한 선택지

| 선택지 | 정확성 | 성능 | 복구 가능성 | 판단 |
| --- | --- | --- | --- | --- |
| DB 실시간 집계 쿼리 단독 | 항상 정확하다. | 조회 트래픽이 많으면 부담이 된다. | 원천 데이터 자체이므로 복구가 필요 없다. | 보류. 정확하지만 확장성이 약하다. |
| Redis ZSET 사전집계 단독 | 갱신 실패·유실 시 부정확할 수 있다. | 조회가 빠르다. | 원천이 없으면 복구 불가능하다. | 제외. "정확해야 한다"는 요구사항과 상충한다. |
| DB(원천) + Redis ZSET(파생) 병행 | DB가 항상 원천이라 언제든 정답을 재계산할 수 있다. | 기본 조회는 Redis라 빠르다. | ZSET이 어긋나도 DB로 재계산 가능하다. | 채택. |

## 결정과 이유

DB(`orders`)를 원천 데이터로, Redis ZSET을 파생 데이터로 둔다. 일자별 키(`popular:menus:{date}`)로 최근 7일 슬라이딩 윈도우를 표현하고, 조회 시 최근 7일치 키를 `unionAndStore`로 합산한 뒤 상위 후보를 애플리케이션에서 (주문수 내림차순, 메뉴ID 오름차순)으로 재정렬한다. ZSET 갱신은 요청 경로가 아니라 `RankingEventConsumer`가 Kafka 이벤트를 소비해 비동기로 수행한다(ADR-003). Redis 결과가 비어 있으면 `OrderRepository`의 DB 집계 쿼리로 폴백한다.

## 결과와 단점

조회는 대부분 Redis로 빠르게 처리되면서도, DB가 항상 재계산 가능한 원천으로 남는다. 반면 주문 성공과 랭킹 반영 사이에 이벤트 소비 지연만큼의 결과적 일관성(eventual consistency) 구간이 생긴다. Kafka replay 기반 자동 복구는 이번 스코프에서 구현하지 않고, `order.completed` topic retention을 7일보다 길게 유지한다는 전제조건만 남겨 향후 확장 여지를 열어둔다.

## 검증 현황과 계획

- 실제 근거: 없음 (스켈레톤 단계).
- 계획된 검증: 주문 발생 후 Redis ZSET 값과 DB 집계 쿼리(`findTopPaidMenuOrderCounts`) 결과가 최종적으로 일치하는지 통합 테스트로 확인한다. Redis 미기동/빈 상태에서 DB 폴백 경로가 정상 동작하는지도 함께 검증한다.

## 재검토 조건

- 조회 트래픽이 매우 커져 DB 폴백 경로의 부하가 문제가 될 때.
- Kafka replay 기반 자동 복구가 실제로 필요해질 때(별도 ADR로 승격).

## 관련 항목

- 설계: `docs/popular-menu-policy.md`, `docs/design-rationale.md`.
- 구현: `ranking.service.RankingService`, `event.consumer.RankingEventConsumer`, `order.repository.OrderRepository#findTopPaidMenuOrderCounts`.
- 대체·폐기 규칙: [ADR 운영 규칙](adr-readme.md)을 따른다.
