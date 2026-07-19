# ADR-008 프로덕션 확장 시 Redis Sentinel 도입

## 상태와 결정일

Superseded by [ADR-009](ADR-009-containerize-app-for-sentinel-ha.md). 결정일: 2026-07-20. 대체일: 2026-07-20.

> 이 문서는 "Sentinel 채택은 결정하되 로컬 인프라에는 넣지 않는다"는 당시 판단과 그 근거를
> 남기기 위해 그대로 보존한다. 실제로는 같은 날 대체 결정(ADR-009)이 이어졌다 — app을
> 컨테이너화하면 아래 47행에서 우려한 "Sentinel의 Docker 내부 주소를 host 프로세스가 못 찾는"
> 문제 자체가 사라진다는 점을 뒤늦게 확인했기 때문이다. 아래 본문은 수정하지 않는다.

## 맥락과 문제

현재 구성(로컬 개발/과제 검증용)은 단일 Redis 인스턴스다. Redisson 분산 락(ADR-002)은 주문·포인트
충전 경로의 1차 방어선이자 유일한 앞단 동시성 제어 장치인데, Redis가 죽으면 대체 경로가 없어
`ORDER_LOCK_NOT_ACQUIRED`/`POINT_LOCK_NOT_ACQUIRED`로 핵심 기능(주문, 충전) 전체가 막힌다.
인기 메뉴 조회는 이미 DB 폴백이 있어 Redis 장애에도 죽지 않지만(`RankingService`), 분산 락은
그런 폴백 경로 자체가 존재하지 않는다.

이 프로젝트의 지향점은 "다수 서버·다수 인스턴스에서 안정적으로 동작하는 서비스"다. 애플리케이션
인스턴스는 이미 stateless로 설계돼 몇 개를 띄우든 정확성이 보장되지만(`OrderConcurrencyIntegrationTest`로
검증), 그 인스턴스들이 공통으로 의존하는 Redis 자체가 단일 장애점(SPOF)으로 남아있다면 "인스턴스를
아무리 늘려도 서비스 전체는 여전히 하나의 지점에서 무너질 수 있다"는 한계가 생긴다.

## 결정 동인

- 락 가용성이 곧 주문/충전 가용성으로 직결된다 (Redis 장애 = 핵심 기능 전체 마비).
- 장애 감지와 복구가 사람 개입 없이 자동으로 이뤄져야 "다수 인스턴스 환경에서 안전하다"는
  이 프로젝트의 지향점에 인프라 레벨에서도 부합한다.
- 로컬 개발/과제 평가 환경의 실행 복잡도를 과도하게 높이지 않아야 한다.

## 검토한 선택지

| 선택지 | 자동 복구 | 구성 복잡도 | 판단 |
| --- | --- | --- | --- |
| 단일 Redis 인스턴스 (현재) | 없음. 장애 시 락 기능 전체 마비. | 가장 단순. | 로컬 개발/과제 검증용으로 유지. 프로덕션 확장 시엔 부적합. |
| Master-Slave 복제만 | 없음. Master 장애 시 사람이 수동으로 Slave를 승격해야 함. | 복제 설정만 추가. | 제외. 자동 복구가 없어 SPOF가 실질적으로 남는다. |
| Redis Sentinel (Master-Slave + Sentinel) | 있음. 쿼럼 합의로 자동 failover. | Sentinel 노드(홀수 개, 보통 3대) 추가 관리 필요. | 프로덕션 확장 시 채택. |
| Redis Cluster (샤딩) | 있음(샤딩 + failover). | 가장 복잡. 슬롯 기반 샤딩으로 재설계 필요. | 보류. 현재 스코프(락/ZSET 위주, 단일 키 공간)에는 과한 복잡도. |

## 결정과 이유

프로덕션으로 확장한다면 Redis Sentinel(Master 1 + Slave 2 + Sentinel 3, 홀수 쿼럼)을 도입한다.
Redisson은 `useSentinelServers()`로 애플리케이션 코드 변경 없이 설정만 바꿔 Sentinel 인식
클라이언트로 전환할 수 있다. Sentinel이 Master 장애를 감지하면 쿼럼 투표로 Slave 중 하나를 새
Master로 승격시키고, Redisson 클라이언트는 새 Master 주소를 자동으로 받아 재연결한다.

다만 이번 과제 스코프(로컬 개발/평가 환경)에서는 `docker-compose`에 Sentinel 전체 토폴로지
(컨테이너 6개: Master 1 + Slave 2 + Sentinel 3)를 넣지 않고 단일 Redis 인스턴스 구성을
유지하기로 했다. 로컬 실행 복잡도 대비 이 프로젝트에서 얻는 검증 가치가 낮고, Sentinel의 핵심
가치인 자동 failover는 "Master를 강제로 종료해보는" 수동 시나리오로만 실증 가능해 자동화된 테스트
근거를 남기기 어렵다. 과제 평가자가 로컬에서 이 프로젝트를 실행해볼 때 인프라 진입장벽만 높이는
결과가 될 수 있다고 판단했다.

## 결과와 단점

설계 의도와 프로덕션 확장 경로를 명확히 남길 수 있지만, 실제 Sentinel 구성이 로컬 인프라에 없어
failover 동작 자체를 이 프로젝트 안에서 실증하지는 못한다. Redis Cluster 대비 데이터 샤딩은
되지 않으므로, 데이터 볼륨 자체가 단일 Redis 인스턴스 용량을 넘어서는 시나리오(랭킹 ZSET 키
폭증 등)에는 Sentinel만으로는 대응할 수 없고 별도 검토가 필요하다.

## 검증 현황과 계획

- 실제 근거: 없음. 로컬 인프라(`docker/docker-compose.yml`)는 단일 Redis 인스턴스를 유지한다.
- 계획된 검증: 프로덕션 도입 시 Sentinel 3대 중 1대를 강제 종료한 뒤 자동 승격과 Redisson
  재연결까지 걸리는 시간(failover 소요 시간)을 측정하고, 그 시간 동안 발생한 락 획득 실패율을
  k6(`k6/order-concurrency-test.js` 확장)로 계측한다.

## 재검토 조건

- 실제 프로덕션 배포가 결정될 때.
- 로컬 부하 테스트(k6)에서 단일 Redis 인스턴스의 처리량 또는 가용성이 병목으로 확인될 때.

## 관련 항목

- 설계: `docs/order-policy.md`, `docs/point-policy.md`, ADR-002(Redisson과 DB 비관적 락).
- 구현: `common.config.RedissonConfig`(현재 `useSingleServer()`), `docker/docker-compose.yml`(단일 Redis 유지).
- 대체·폐기 규칙: [ADR 운영 규칙](README.md)을 따른다.
