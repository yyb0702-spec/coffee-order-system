# coffee-order-system

커피숍 주문 시스템 백엔드. 다수 서버·다수 인스턴스 환경에서 안전하게 동작하는 것을 전제로,
메뉴 조회 / 포인트 충전 / 주문·결제 / 인기 메뉴 랭킹 4개 기능을 구현했다.

이 문서는 과제가 요구하는 README 필수 항목(설계 내용, 설계의 의도, 선택한 문제해결 전략 및
분석 내용, 기술적 선택 이유)을 담는다. 세부 근거는 각 절의 링크로 연결된 문서에 있다.

## 목차

- [빠른 시작](#빠른-시작)
- [설계 내용](#설계-내용)
- [설계의 의도](#설계의-의도)
- [선택한 문제해결 전략 및 분석 내용](#선택한-문제해결-전략-및-분석-내용)
- [기술적 선택 이유](#기술적-선택-이유)
- [도전 요구사항 대응 현황](#도전-요구사항-대응-현황)
- [테스트](#테스트)
- [향후 확장 과제](#향후-확장-과제)

## 빠른 시작

```bash
# 1. 로컬 인프라(MySQL/Redis/Kafka) 기동
docker compose -f docker/docker-compose.yml up -d

# 2. 애플리케이션 실행 (Flyway가 기동 시점에 스키마/시드 데이터를 적용한다)
./gradlew bootRun

# 3. 동시성/Kafka 통합 테스트 (Testcontainers가 별도 컨테이너를 띄우므로 Docker 데몬 필요)
./gradlew test

# 4. 부하 테스트 (선택)
k6 run -e BASE_URL=http://localhost:8080 k6/order-concurrency-test.js
```

애플리케이션 접속 정보(DB/Redis/Kafka)는 `src/main/resources/application.yaml`과
`docker/docker-compose.yml`이 항상 일치하도록 관리한다.

## 설계 내용

- **ERD / DB 스키마**: [`docs/db-schema.sql`](docs/db-schema.sql) (ERDCloud import 가능한 DDL), 그림은
  [`docs/erd/ERD 초안.png`](<docs/erd/ERD 초안.png>). 실제 적용되는 마이그레이션은
  [`src/main/resources/db/migration`](src/main/resources/db/migration)이며 `docs/db-schema.sql`과 항상 동일하게 유지한다.
- **API 명세서**: [`docs/api/api-spec.md`](docs/api/api-spec.md) — 요청/응답 예시, 에러코드 표, `Idempotency-Key`
  헤더 요구사항을 포함한다.
- **도메인별 정책 문서**: [`docs/domain/domain-policy.md`](docs/domain/domain-policy.md),
  [`docs/domain/order-policy.md`](docs/domain/order-policy.md),
  [`docs/domain/point-policy.md`](docs/domain/point-policy.md),
  [`docs/domain/popular-menu-policy.md`](docs/domain/popular-menu-policy.md) — 각 도메인의 동시성 제어, 장애 대응,
  일관성 수준을 규칙 단위로 정리했다.
- **설계 결정 기록(ADR)**: [`docs/adr/`](docs/adr) — 결정마다 검토한 대안, 결정 동인, 결과와 단점, 검증
  현황을 남긴다. 운영 규칙은 [`docs/adr/README.md`](docs/adr/README.md) 참고.
- **API 수동 테스트 산출물**: [`http/api-requests.http`](http/api-requests.http) — 4개 API 정상 케이스와
  주요 에러 케이스(중복 요청, 유효성 실패, 존재하지 않는 메뉴 등)를 IntelliJ HTTP Client 형식으로 정리했다.

| ADR | 내용 |
| --- | --- |
| [ADR-001](docs/adr/ADR-001-layered-architecture.md) | 도메인 우선 패키지 + Controller-Service-Repository 3계층 |
| [ADR-002](docs/adr/ADR-002-redisson-and-db-pessimistic-lock.md) | Redisson 분산 락 + DB 비관적 락 이중 구조 |
| [ADR-003](docs/adr/ADR-003-kafka-for-event-publishing.md) | 이벤트 발행에 Kafka 사용, 재시도(FixedBackOff)+DLT |
| [ADR-004](docs/adr/ADR-004-redis-zset-ranking.md) | Redis ZSET 인기 메뉴 랭킹, DB 폴백/재구성 |
| [ADR-005](docs/adr/ADR-005-point-ledger-table.md) | 포인트 원장(ledger) 테이블 도입 |
| [ADR-006](docs/adr/ADR-006-idempotency-key.md) | Idempotency-Key로 중복 요청 차단 |
| [ADR-007](docs/adr/ADR-007-testcontainers-and-k6.md) | Testcontainers + k6 테스트 전략 |

## 설계의 의도

이 과제는 정답이 정해진 문제가 아니라 "왜 이 구조를 선택했는가"를 설명하고 설득하는 것이
평가 기준이라는 점을 가장 먼저 고려했다. 그래서 개별 기술을 화려하게 쌓기보다, 아래 다섯 가지
원칙을 모든 결정의 기준으로 삼았다.

1. **DB를 항상 정합성의 원천(source of truth)으로 둔다.** Redis, Kafka는 파생 데이터 또는 비동기
   처리 계층으로만 사용하고, 언제든 DB로부터 재계산·재발행이 가능해야 한다.
2. **정확성을 담당하는 장치와 성능/트래픽을 담당하는 장치를 분리한다.** 예를 들어 포인트 차감의
   최종 정합성은 DB 락이 책임지고, Redisson은 트래픽 방어용 필터로만 동작한다. 장치마다 역할이
   명확해야 설계 의도를 설명할 수 있다.
3. **외부 시스템 연동은 핵심 트랜잭션 밖에 둔다.** 데이터 수집 플랫폼 전송 같은 외부 호출이
   실패해도 결제 성공 여부에는 영향이 없어야 한다.
4. **다중 서버·다중 인스턴스를 전제로 한다.** 로컬 메모리 상태(synchronized, 로컬 캐시)에
   의존하는 동시성 제어는 배제하고, 모든 제어는 DB·Redis 같은 공유 자원을 통해 이루어지게 한다.
5. **MVP 범위와 확장 과제를 명확히 구분한다.** 모든 것을 구현하려 하지 않고, 이번 구현 범위와
   [향후 확장 과제](#향후-확장-과제)로 남긴 부분을 문서에 명시해 판단 근거를 남긴다.

## 선택한 문제해결 전략 및 분석 내용

### 1. 포인트 동시 차감

**문제**: 동일 사용자의 동시 주문 요청이 같은 잔액을 동시에 읽고 중복 차감할 수 있다.

**검토한 대안**: (a) 원자적 UPDATE 단독, (b) DB 비관적 락 단독, (c) 낙관적 락(버전 + 재시도),
(d) 비관적 락 + Redisson 분산 락 병행.

**분석**: 주문 처리는 "잔액 확인 → 차감 → 포인트 원장 insert → 주문 insert"로 이어지는 여러
단계로 구성돼 있어, 단일 원자적 UPDATE로는 전체 임계구역을 보장할 수 없다고 판단했다. 낙관적
락은 동시 요청이 몰리는 이 과제의 테스트 시나리오 특성상 재시도 비용이 커질 수 있어 배제했다.
DB 비관적 락만으로도 다중 인스턴스 환경에서 정확성은 보장되지만, 트래픽이 몰릴 때 불필요하게
락 대기가 길어지는 문제가 남는다. 이를 완화하기 위해 Redisson으로 앞단에서 동일 사용자의 요청
진입 자체를 직렬화하는 이중 구조를 택했다.

**결론**: DB 비관적 락(최종 정합성 보장) + Redisson(트래픽 방어용 필터)의 역할 분리 구조.
`OrderConcurrencyIntegrationTest`가 초기 10,000P/메뉴 4,000P/동시 10건 시나리오(성공 2건,
실패 8건, 최종 잔액 2,000P)로 실제 근거를 남긴다.

같은 구조를 포인트 충전(`PointChargeService`)에도 적용했다. 최초 충전(아직 `user_point` row가
없는 사용자)에 대한 동시 요청은 `findByUserIdForUpdate`가 잠글 row 자체가 없어 DB 비관적 락만으로는
막지 못하는 레이스였는데, `point-lock:{userId}` Redisson 락으로 요청 진입 자체를 직렬화해 해결했다.

### 2. 외부 데이터 수집 플랫폼 연동

**문제**: 주문 내역을 외부 플랫폼에 실시간 전송해야 하지만, 결제 트랜잭션 안에서 동기로
호출하면 외부 장애가 결제 실패로 전파될 위험이 있다.

**검토한 대안**: (a) 결제 트랜잭션 내부 동기 호출, (b) `AFTER_COMMIT` 비동기 이벤트,
(c) Kafka 발행 + 별도 Consumer 전달.

**분석**: (a)는 네트워크 지연·장애가 곧 결제 실패로 이어지는 치명적 리스크가 있어 제외했다.
(b)는 별도 인프라 없이 구현할 수 있지만, 인스턴스 재시작 시 이벤트가 유실될 수 있고 다중
인스턴스 환경에서의 전달 보장이 약하다. 이 과제의 도전 요구사항("다수 서버에 다수의 인스턴스로
동작")과 직접 맞닿아 있는 지점이라 판단해 Kafka를 선택했다. 파티션 기반 순서 보장과 Consumer
Group을 통한 재시도·DLT 처리를 표준화된 방식으로 다룰 수 있다는 점이 결정적이었다.

**결론**: 커밋 이후 `OrderCompletedEvent`를 Kafka(topic `order.completed`)로 발행하고, 소비
실패는 `FixedBackOff(1000ms, 2회)` 재시도 후 `DeadLetterPublishingRecoverer`로 DLT
(`order.completed.DLT`)에 격리한다. `OrderEventKafkaIntegrationTest`가 발행-소비 흐름과
`eventId` 기준 중복 소비 방지를 검증한다.

### 3. 인기 메뉴 집계

**문제**: 최근 7일 주문 횟수를 "정확하게" top3로 조회해야 하는데, 매 요청마다 DB 집계 쿼리를
실행하면 트래픽이 많을 때 부담이 된다.

**검토한 대안**: (a) DB 실시간 쿼리 단독, (b) Redis ZSET 사전집계 단독, (c) DB(원천) + Redis
ZSET(파생) 병행.

**분석**: 요구사항에 "정확해야 한다"는 제약이 명시돼 있어 캐시에만 의존하는 (b)는 위험하다고
판단했다. 반대로 (a)만 쓰면 조회가 몰릴 때 확장성이 떨어진다. 이미 Kafka를 도입했으므로, 이벤트
소비를 통해 ZSET을 비동기로 갱신하는 방식이 자연스럽게 이어졌고, DB를 원천으로 유지해 ZSET이
어긋나도 언제든 재계산할 수 있는 안전장치를 마련했다.

**결론**: DB(원천, 검증·폴백용) + Redis ZSET(파생, 빠른 조회용) 병행. 일자별 키
(`popular:menus:{date}`)로 최근 7일 슬라이딩 윈도우를 구현하고, Redis 결과가 비어 있으면 DB
집계 쿼리로 자동 폴백한다. Redis 데이터가 유실됐을 때는 `POST /api/menus/popular/rebuild`로
DB 기준 재구성(rebuild)을 수동 트리거할 수 있다.

### 4. 포인트 이력 관리

**문제**: 잔액 컬럼 하나만으로는 충전/사용 내역을 추적할 수 없고, 정합성을 검증할 근거도 없다.

**검토한 대안**: (a) `balance` 컬럼만 유지, (b) 충전/사용 이력을 남기는 원장 테이블 추가.

**분석**: MVP 요구사항만 보면 (a)로도 API 스펙은 충족한다. 하지만 "데이터 일관성"이 도전
요구사항으로 명시돼 있어, 잔액의 근거를 추적하고 검증할 수 있는 구조가 더 설득력 있다고
판단했다.

**결론**: `point_transaction` 원장 테이블을 도입하고, `user_point.balance`는 조회 편의를 위한
캐시값으로 유지한다.

### 5. 중복 요청 방지

**문제**: 네트워크 재시도(클라이언트 타임아웃 후 재요청 등)로 같은 주문/충전 요청이 두 번
전송될 수 있다. 동시성 락(1번 항목)은 "같은 시점의 경합"은 막지만 "시간차를 둔 중복 요청"은
막지 못한다.

**검토한 대안**: (a) 미도입, (b) "조회 후 없으면 insert", (c) unique 제약 테이블에 즉시 insert
시도 후 실패 시 중복 처리.

**분석**: (b)는 조회와 insert 사이에 레이스가 생겨 다중 인스턴스 환경에서 두 요청이 동시에
통과할 수 있는 TOCTOU 문제가 있다. (c)는 DB unique 제약이 원자성을 보장하므로 인스턴스가
몇 개든 안전하다.

**결론**: `idempotency_key` 테이블에 `Idempotency-Key` 헤더값을 본 비즈니스 트랜잭션과 같은
트랜잭션 안에서 즉시 insert 시도하고, 제약 위반이면 `DUPLICATE_REQUEST`(409)로 응답한다.
같은 트랜잭션에 두는 이유는, 별도 트랜잭션으로 먼저 커밋하면 본 로직이 롤백됐을 때 키만 남아
해당 요청이 영구히 재시도 불가능해지기 때문이다.

### 6. 테스트 전략

**문제**: 동시성·락 로직을 어떻게 신뢰성 있게 검증할 것인가.

**검토한 대안**: H2 인메모리 DB vs Testcontainers(MySQL).

**분석**: `SELECT ... FOR UPDATE` 같은 락 동작은 DB 벤더마다 미묘하게 다르게 동작할 수 있어,
H2로는 실제 운영 환경과 같은 수준의 신뢰도를 담보할 수 없다고 판단했다. "동시성 이슈"가 도전
요구사항의 핵심이므로, 초기 잔액·동시 요청 수·기대 성공/실패 건수를 수치로 고정한 재현 가능한
테스트 시나리오를 만들고, k6로 부하 상황에서의 실제 동작까지 관찰하기로 했다.

**결론**: Testcontainers(MySQL/Kafka)로 락·트랜잭션·이벤트 통합 테스트를 작성하고, k6로 동시
요청 부하 테스트를 병행한다.

## 기술적 선택 이유

| 기술 | 선택 이유 |
| --- | --- |
| MySQL | 관계형 정합성(FK, 트랜잭션, 행 락)이 핵심 요구사항이라 RDBMS가 적합. 프로젝트 스켈레톤에 이미 지정됨. |
| Redisson | 로컬 `synchronized`는 다중 인스턴스 환경에서 의미가 없어, Redis 기반 분산 락이 필요했다. Spring 생태계에서 lease time·재시도 옵션을 갖춘 표준적인 선택지다. |
| Kafka | 단순 메시지 큐보다 파티션 기반 순서 보장과 Consumer Group을 통한 수평 확장이 "다수 서버·다수 인스턴스" 요구사항에 더 적합하다고 판단했다. 로그 기반이라 재처리(replay)가 가능해 랭킹 복구 시나리오에도 유리하다. |
| Redis (ZSET) | 정렬된 집합 자료구조가 top-N 랭킹 조회에 최적화돼 있고, 일자별 키 분리로 슬라이딩 윈도우를 구현할 수 있다. |
| Flyway | JPA `ddl-auto`는 인스턴스마다 스키마를 자동 변경할 위험이 있어 다중 인스턴스 환경에 부적합하다. 마이그레이션 파일로 스키마를 명시적으로 버전 관리한다(`ddl-auto: validate`로 드리프트를 방지). |
| Testcontainers | H2와 실제 MySQL/Kafka의 동작 차이로 인한 거짓 양성/음성을 피하기 위해, 락·트랜잭션·이벤트 검증은 실제 인프라 수준으로 맞췄다. |
| k6 | 도전 요구사항(동시성)에 대한 정량적 근거(처리량, 응답시간, 동시 요청 성공/실패 분포)를 남기기 위해 채택했다. |

## 도전 요구사항 대응 현황

| 요구사항 | 대응 |
| --- | --- |
| 다수 서버·다수 인스턴스에서의 안전한 동작 | 락 상태를 인스턴스 로컬 메모리가 아닌 Redis(Redisson)·DB에 두어 인스턴스 수와 무관하게 정확성을 보장한다. `docker-compose`로 인프라를, k6 다중 `BASE_URLS` 옵션으로 다중 인스턴스 시나리오를 재현할 수 있다. |
| 동시성 처리 | Redisson 분산 락(1차) + DB 비관적 락(최종). 주문(`OrderService`)과 포인트 충전(`PointChargeService`) 모두 동일 구조. `OrderConcurrencyIntegrationTest`로 수치 검증. 락 해제(`unlock()`) 실패가 이미 성공한 응답을 덮어쓰지 않도록 별도 방어 처리. |
| 데이터 일관성 | `point_transaction` 원장을 원천으로, `user_point.balance`를 캐시로 분리. Kafka 이벤트는 `AFTER_COMMIT`에만 발행해 결제 트랜잭션과 분리. 랭킹 Consumer는 ledger(`ProcessedEvent`) DB insert를 Redis 반영보다 먼저 수행해, 트랜잭션 롤백 후 재시도되는 경우에도 Redis가 이중으로 증가하지 않게 했다. |
| 각 기능/제약별 테스트 | `OrderConcurrencyIntegrationTest`(동시성), `OrderEventKafkaIntegrationTest`(이벤트 발행-소비, 멱등성), `IndexUsageVerificationTest`(EXPLAIN 기반 인덱스 사용 검증), `CoffeeOrderSystemApplicationTests`(컨텍스트 로딩). |
| 데이터 수집 플랫폼 실시간 전송(모의) | `event.producer.OrderEventProducer`가 Kafka로 발행하는 것을 실시간 전송의 진입점으로 삼았다. 실제 외부 플랫폼 연동은 이 topic을 구독하는 별도 Consumer(모의/테스트 코드)로 대체 가능하다. |
| 장애 복구 운영 도구 | Redis 유실 시 DB 재계산(`POST /api/menus/popular/rebuild`), Kafka 소비 실패 누적 시 DLT 수동 재발행(`POST /api/admin/dlt/replay`), ledger 보존기간 정리(`POST /api/admin/processed-events/purge`, 매일 자동 실행). Redis 연결 자체가 끊긴 경우에도 인기 메뉴 조회가 500으로 죽지 않고 DB 폴백으로 전환된다(`RankingService`). |

## 테스트

```bash
./gradlew test
```

- `OrderConcurrencyIntegrationTest`: Testcontainers MySQL/Redis/Kafka로 실제 인프라를 띄워
  동시 주문 10건 시나리오(성공 2건/실패 8건/잔액 2,000P)를 검증한다.
- `OrderEventKafkaIntegrationTest`: Testcontainers Kafka로 `OrderCompletedEvent` 발행-소비와
  `eventId` 기준 중복 소비 방지를 검증한다.

Testcontainers를 사용하므로 로컬에 Docker 데몬이 실행 중이어야 한다. 부하 테스트는
[`k6/README.md`](k6/README.md)를 참고한다.

## 향후 확장 과제

이번 구현 범위에서 의도적으로 제외하고 향후 확장 여지로 남긴 부분이다 (설계 원칙 5번).

- **DLT 자동 재처리**: `POST /api/admin/dlt/replay`로 수동 트리거는 가능하지만, 실패 원인을
  자동 판별해 재발행 여부를 결정하는 배치/스케줄러는 아직 없다. 운영자가 원인을 확인한 뒤
  트리거하는 것을 전제로 한다.
- **Kafka replay 기반 랭킹 자동 복구**: `POST /api/menus/popular/rebuild`로 DB 기준 수동
  재구성은 가능하지만, `order.completed` topic을 replay해 자동으로 복구하는 절차는 별도
  ADR로 승격할 사안으로 남겨뒀다.
- **Idempotency Key(`idempotency_key` 테이블) 정리(TTL/배치 삭제)**: Kafka ledger
  (`processed_event`)는 30일 보존기간 정리를 구현했지만(`ProcessedEventRetentionService`),
  주문/충전 API의 `idempotency_key` 테이블은 아직 정리 정책이 없다.
- **인증/인가**: `userId`를 요청 바디로 직접 받는 구조로, 별도 인증 체계는 이번 스코프 밖이다.
  `/rebuild`, `/api/admin/**` 운영 엔드포인트도 같은 이유로 인증 없이 열려 있다.
- **QueryDSL 도입**: 쿼리 인덱스 사용 여부는 `IndexUsageVerificationTest`(EXPLAIN)로 검증했지만,
  타입 세이프 쿼리 빌더(QueryDSL) 자체는 도입하지 않았다. 이 환경에서 Gradle 애노테이션 프로세서
  설정을 실제로 컴파일 검증할 수 없어, 빌드를 깨뜨릴 위험을 감수하지 않기로 판단했다.
