# 포인트 최초 충전 시 락 걸 대상이 아직 존재하지 않는 동시성 레이스

## 문제 상황

포인트 충전은 `user_point` 테이블의 유저별 행을 갱신하는 작업이다. 이미 충전 이력이 있는
유저라면 `SELECT ... FOR UPDATE`로 그 행을 잠그고 갱신하면 동시 요청에도 안전하다.

문제는 **아직 한 번도 충전한 적 없는 신규 유저**다. 이 경우 `user_point`에 그 유저의 행 자체가
없다. "행이 없으면 새로 만든다"는 로직으로 처리했는데, 동시에 같은 유저의 첫 충전 요청 두 개가
들어오면 두 스레드 모두 "행이 없다"고 판단하고 각자 `INSERT`를 시도할 수 있다. `user_id`에
유니크 제약이 걸려 있으니 둘 중 하나는 `DataIntegrityViolationException`을 맞게 되고, 이게
그대로 전파되면 500 에러로 이어질 수 있는 구조였다.

## 원인

`SELECT ... FOR UPDATE` 기반 비관적 락은 **잠글 행이 이미 존재해야** 의미가 있다. 아직 없는
행을 만드는 순간에는 잠글 대상 자체가 없으므로, "동시성 처리를 이미 해뒀다"는 인식과 실제
코드가 커버하는 범위 사이에 간극이 있었다. 이미 있는 데이터를 갱신하는 시나리오만 생각하고,
데이터가 아직 없는 최초 시나리오를 놓친 것이다.

## 해결

이미 주문(`OrderService`)에 적용해뒀던 Redisson 분산 락 패턴을 포인트 충전에도 동일하게
적용했다. DB 레벨의 락이 아니라, **userId 단위로 충전 요청 자체의 진입을 직렬화**하는
방식이라 행의 존재 여부와 무관하게 동작한다.

```java
// src/main/java/.../point/service/PointChargeService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class PointChargeService {

    private static final String LOCK_KEY_PREFIX = "point-lock:";
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final long LOCK_LEASE_SECONDS = 5L;
    private final RedissonClient redissonClient;
    private final PointService pointService;

    public PointChargeResponse charge(String idempotencyKey, PointChargeRequest request) {
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + request.userId());
        boolean acquired;
        try {
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.POINT_LOCK_NOT_ACQUIRED);
        }
        if (!acquired) {
            throw new BusinessException(ErrorCode.POINT_LOCK_NOT_ACQUIRED);
        }
        try {
            return pointService.charge(idempotencyKey, request);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("포인트 충전 락 해제에 실패했습니다. leaseTime 이후 자동 만료됩니다. userId={}",
                            request.userId(), e);
                }
            }
        }
    }
}
```

`PointController`가 `PointService`를 직접 호출하던 것을 이 `PointChargeService`를 거치도록
바꿨다. 같은 `userId`로 들어오는 두 번째 요청은 첫 번째 요청이 락을 쥐고 있는 동안 `tryLock`
대기(`LOCK_WAIT_SECONDS`)에 걸리고, 첫 요청이 끝나 락을 놓은 뒤에야 순서대로 처리된다 — 그
시점엔 이미 첫 요청이 행을 만들어뒀으니 두 번째 요청은 정상적으로 기존 행을 갱신하는 경로를
탄다. 유니크 제약 위반이 날 여지 자체가 사라진다.

## 검증

`OrderConcurrencyIntegrationTest`가 검증하는 것과 같은 패턴(Testcontainers + `ExecutorService`
동시 호출)으로 재현/검증 가능한 구조로 만들어뒀고, 로컬에서 k6로 실제 동시 요청 부하까지
확인했다(README의 [로컬 실행 검증](../../README.md#로컬-실행-검증) 참고).

## 배운 점

동시성 처리를 설계할 때 "이미 존재하는 데이터를 여러 요청이 동시에 갱신하는 경우"만 생각하기
쉽다. 하지만 "아직 존재하지 않는 데이터를 여러 요청이 동시에 처음 만들려는 경우"는 잠글 대상
자체가 없어서 완전히 다른 종류의 레이스 컨디션이 된다. DB 행 레벨 락으로는 이 케이스를 막을 수
없고, 이번처럼 애플리케이션 레벨에서 "그 리소스에 대한 진입 자체"를 락으로 직렬화하는 방식이
필요하다.
