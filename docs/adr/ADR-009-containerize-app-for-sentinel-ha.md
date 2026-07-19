# ADR-009 앱 컨테이너화로 Redis Sentinel 인프라 가용성 실증

## 상태와 결정일

Accepted. 결정일: 2026-07-20. [ADR-008](ADR-008-redis-sentinel-for-scale.md)을 대체한다.

## 맥락과 문제

ADR-008에서 프로덕션 확장 시 Redis Sentinel 도입을 결정했지만, 로컬 `docker-compose`에는 넣지
않기로 했다. 이유는 두 가지였다: (1) 이 과제의 앱은 host에서 `./gradlew bootRun`으로 직접
실행되는데, Sentinel은 클라이언트에게 Master 주소를 "자신이 알고 있는 그대로" 알려준다. Docker
네트워크 안에서 Sentinel이 보고하는 주소는 `redis-master:6379` 같은 컨테이너 내부 호스트명이고,
host 프로세스는 이 이름을 resolve할 수 없다 — Kafka `KAFKA_ADVERTISED_LISTENERS`에서 이미 한 번
겪은 것과 같은 종류의 문제다. (2) 이걸 억지로 풀려면 `sentinel announce-ip`로 host가 접근 가능한
주소를 강제로 광고시켜야 하는데, 이 방식은 이 샌드박스에서 실행·검증할 수 없어 정확성을 보장하기
어려웠다.

그런데 이 프로젝트의 지향점 자체가 "다수 인스턴스 환경에서 안정적으로 동작하는 서비스"이고,
ADR-008이 이미 지적했듯 애플리케이션 인스턴스가 아무리 stateless여도 그 인스턴스들이 공통으로
의존하는 Redis가 단일 장애점(SPOF)으로 남으면 "인스턴스 레벨 정확성"과 "서비스 레벨 가용성"
사이에 실질적인 간극이 생긴다. ADR-008은 이 간극을 문서로만 남기고 실제로 메우지는 않았다.

## 결정 동인

- ADR-008이 실제로 넘지 못한 장벽은 "host 프로세스가 Docker 내부 주소를 못 찾는다"는 것 하나였다.
  앱 자체를 컨테이너화하면 이 장벽이 정의상 사라진다 — 모든 서비스가 같은 docker 네트워크 안에서
  서비스 이름으로 서로를 찾을 수 있다.
- `announce-ip` 같은 host-Docker 혼합 네트워킹 우회책보다, 앱을 컨테이너화하는 쪽이 더 적은
  가정으로 동작을 설명할 수 있다 (검증 불가능한 네트워킹 트릭에 기대지 않는다).
- 빠른 반복 개발(`bootRun`)과 인프라 가용성 실증(컨테이너화 전체 스택)은 서로 다른 목적이므로,
  하나를 없애지 않고 두 경로를 모두 남기는 쪽이 안전하다.

## 검토한 선택지

| 선택지 | 설명 | 판단 |
| --- | --- | --- |
| A. host 앱 + `announce-ip` | 앱은 계속 host에서 bootRun, Sentinel에 host가 접근 가능한 주소를 강제로 광고시킴 | 제외. 이 샌드박스에서 실행·검증이 불가능한 네트워킹 설정에 의존하게 되어 정확성을 보장할 수 없다. |
| B. 앱 컨테이너화 | Dockerfile로 앱을 빌드해 docker-compose의 한 서비스로 편입, Sentinel/Kafka와 같은 네트워크 공유 | 채택. host-Docker 경계 자체가 없어져 별도 우회책이 불필요하다. |
| C. ADR-008 그대로 유지 (문서화만) | Sentinel을 로컬에 넣지 않고 프로덕션 결정으로만 남김 | 제외. 이 프로젝트의 핵심 목표(다수 인스턴스 환경에서의 안정성)를 인프라 레벨에서 실증할 기회를 스스로 포기하는 셈이라고 판단했다. |

## 결정과 이유

앱을 컨테이너화해 `docker-compose`의 `app` 서비스로 편입하고, Redis는 Sentinel 토폴로지
(Master 1 + Replica 2 + Sentinel 3, 쿼럼 2)로 별도 구성한다. `app` 서비스는 Sentinel 노드
주소(`redis-sentinel-1:26379` 등)를 환경변수로 받아 Redisson과 Spring Data Redis 양쪽 모두
Sentinel 인식 모드로 접속한다.

- `common.config.RedissonConfig`(기존, 단일 서버)와 `common.config.RedissonSentinelConfig`(신규)를
  `redisson.mode` 프로퍼티로 상호 배타적으로 활성화한다 (`@ConditionalOnProperty`,
  `matchIfMissing=true`로 단일 서버가 기본값). `./gradlew bootRun`과 Testcontainers 통합 테스트는
  이 프로퍼티를 설정하지 않으므로 그대로 단일 서버 모드를 쓴다 — 기존 테스트/개발 흐름은 변경 없음.
- Spring Data Redis(`StringRedisTemplate`)는 `spring.data.redis.sentinel.master`/`.nodes`
  프로퍼티가 있으면 Spring Boot가 자동으로 Sentinel 커넥션으로 전환한다. 별도 Java 설정이
  필요 없다.
- Kafka는 리스너를 INTERNAL(`kafka:29092`, 컨테이너 간)과 EXTERNAL(`localhost:9092`, host
  bootRun용)로 분리했다. 컨테이너화된 `app`은 INTERNAL을, host bootRun은 계속 EXTERNAL을 쓴다.
- 로컬에서 빠르게 코드를 반복 확인하고 싶을 때는 기존처럼 `docker compose up -d mysql redis kafka`
  + `./gradlew bootRun` 조합을 그대로 쓸 수 있다. Sentinel/failover를 보고 싶을 때만
  `docker compose up -d --build`로 전체 스택(앱 포함)을 띄운다.

## 결과와 단점

로컬에서 띄워야 하는 컨테이너가 늘어난다 (mysql, redis, kafka, redis-master, replica 2개,
sentinel 3개, app — 총 9개). 이 중 `redis`(단일 인스턴스)와 Sentinel 토폴로지는 동시에 떠 있어도
서로 간섭하지 않지만, 둘 다 필요하지 않은 경우에는 리소스 낭비다. Kafka 리스너를 두 개로 나누면서
설정이 한 단계 더 복잡해졌다.

중요한 제약: 이 작업 환경에는 실제 Docker 데몬이 없어 `Dockerfile` 빌드, `docker compose up`,
Sentinel failover 동작을 이 환경에서 직접 실행해 검증하지는 못했다. 문법과 설정값은 공식 문서
기준으로 작성했고, 사용자가 로컬에서 직접 기동·failover까지 확인했다(아래 검증 현황 참고).
다만 이 관찰은 사용자의 체감 관찰이며, k6 같은 도구로 정밀 계측한 수치는 아니다.

## 검증 현황과 계획

- 실제 근거: 사용자가 로컬에서 `docker compose -f docker/docker-compose.yml up -d --build`로
  전체 스택을 기동하고, `docker stop coffee-order-redis-master`로 Master를 강제 종료해 확인했다.
  Sentinel이 새 Master를 승격시키는 데 체감상 약 10초, 이후 앱(Redisson)이 새 Master로
  재연결해 정상 응답하기까지 추가로 약 3~4초가 걸렸다 — 장애 발생부터 앱이 다시 정상
  처리하기까지 총 13~14초 정도로 관찰됐다. `down-after-milliseconds 5000` + 쿼럼 합의
  시간을 감안하면 설정값과 대략 부합하는 범위다. (스톱워치나 스크립트로 정밀 측정한 값이
  아니라 사용자가 터미널에서 지켜본 체감 수치임을 명시한다.)
- 계획된 검증: 위 수치를 `k6/order-concurrency-test.js`를 failover 시나리오에 맞게 확장해
  정밀하게 재측정하고, failover 구간 동안의 락 획득 실패율(`ORDER_LOCK_NOT_ACQUIRED`/
  `POINT_LOCK_NOT_ACQUIRED` 비율)을 정량적으로 계측한다. 아직 수행하지 않았다.

## 재검토 조건

- 로컬 실행 복잡도(컨테이너 9개)가 과제 평가 환경에서 실질적인 진입장벽으로 확인될 때 —
  이 경우 ADR-008 노선(문서화만, 로컬 미구현)으로 되돌아가는 것을 재검토한다.
- 사용자의 로컬 검증에서 Kafka 이중 리스너나 Sentinel `resolve-hostnames` 설정이 의도대로
  동작하지 않는 것으로 확인될 때.

## 관련 항목

- 대체 대상: [ADR-008 프로덕션 확장 시 Redis Sentinel 도입](ADR-008-redis-sentinel-for-scale.md).
- 설계: ADR-002(Redisson과 DB 비관적 락).
- 구현: `Dockerfile`, `docker/docker-compose.yml`, `docker/redis/sentinel-*.conf`,
  `common.config.RedissonConfig`, `common.config.RedissonSentinelConfig`.
- 대체·폐기 규칙: [ADR 운영 규칙](README.md)을 따른다.
