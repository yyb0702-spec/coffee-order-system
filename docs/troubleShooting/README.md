# 트러블슈팅

구현하면서 실제로 부딪힌 문제와 원인, 해결 방법을 정리한다. "동시성 처리를 했다"/"장애 대응을
했다"는 서술만으로는 드러나지 않는, 구현 중간에 실제로 발견하고 고친 것들이다. 각 문서는
문제 상황 → 원인 → 해결 → 검증 → 배운 점 순서로 구성했다.

## 목록

| 문서 | 요약 |
| --- | --- |
| [01. 랭킹 Redis 이중 카운트](01-ranking-redis-double-count.md) | DB insert와 Redis ZINCRBY의 트랜잭션 경계가 달라 재시도 시 이중 카운트가 발생하던 문제. 작업 순서 재배치로 해결. |
| [02. 락 해제 실패가 성공 응답을 덮어씀](02-lock-unlock-swallows-success.md) | `finally`의 `unlock()` 예외가 이미 성공한 `try`의 리턴값을 덮어쓰던 문제. Java의 try-finally 동작과 lease time 안전망으로 해결. |
| [03. Spring AOP self-invocation](03-spring-aop-self-invocation.md) | 같은 클래스 내부 호출이 프록시를 거치지 않아 `@Transactional`이 조용히 무력화되던 문제. |
| [04. 포인트 최초 충전 동시성 레이스](04-point-charge-concurrency-race.md) | 잠글 행이 아직 없는 신규 유저의 동시 최초 충전 요청이 유니크 제약 위반을 낼 수 있던 문제. Redisson 락으로 진입 자체를 직렬화해 해결. |
| [05. Redis 장애 폴백 예외 처리 누락](05-redis-fallback-missing-exception-handling.md) | ADR-004에 문서화한 장애 폴백이 "결과 없음"만 처리하고 "연결 실패 예외"는 놓치고 있던 문제. |
| [06. Kafka advertised listener 이원화](06-kafka-dual-listener.md) | host 프로세스와 컨테이너가 같은 Kafka 브로커에 서로 다른 이름으로 접속해야 했던 문제. INTERNAL/EXTERNAL 리스너 분리로 해결. |
| [07. Redis Sentinel protected-mode](07-redis-sentinel-protected-mode.md) | Sentinel 토폴로지 설계는 맞았지만 Redis 기본 보안 설정이 컨테이너 간 통신 자체를 막고 있던 문제. 실행 전 리뷰로 사전에 발견. |

관련: [메인 README의 트러블슈팅 요약](../../README.md#트러블슈팅), [ADR 목록](../adr/README.md).
