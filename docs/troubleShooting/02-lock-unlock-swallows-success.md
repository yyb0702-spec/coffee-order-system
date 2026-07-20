# 락 해제 실패가 이미 성공한 응답을 덮어씀

## 문제 상황

`OrderService`와 `PointChargeService`는 Redisson 분산 락을 이렇게 사용한다.

```java
RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + request.userId());
boolean acquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
if (!acquired) {
    throw new BusinessException(ErrorCode.ORDER_LOCK_NOT_ACQUIRED);
}
try {
    return orderPaymentProcessor.pay(idempotencyKey, request); // 본 비즈니스 로직
} finally {
    lock.unlock();
}
```

처음 구현에서는 `finally` 블록에서 `lock.unlock()`을 그냥 호출했다. 이 상태에서 `unlock()`
자체가 예외를 던지면, `try` 블록이 이미 정상적으로 `return`한 값이 있어도 그 값은 무시되고
`unlock()`의 예외가 호출자에게 그대로 전파된다. 결과적으로 주문/충전 로직은 완전히 성공했는데도
사용자에게는 500 에러(또는 처리되지 않은 예외)가 나가는 상황이 생길 수 있었다.

## 원인

이건 Redisson 특유의 버그가 아니라 Java 언어 자체의 동작이다. `try` 블록에 `return`이 있고
`finally` 블록에서 예외가 발생하면, `finally`의 예외가 `return` 값을 덮어쓰고 전파된다. 즉
"본 로직은 끝까지 다 성공했다"는 사실 자체가 사라진다.

`unlock()`이 실패할 수 있는 현실적인 경우도 있다.

- lease time(`LOCK_LEASE_SECONDS`)이 이미 지나서 Redisson이 락을 자동 해제한 뒤인데, 뒤늦게
  `unlock()`을 호출하면 `IllegalMonitorStateException`류의 예외가 날 수 있다.
- Redis 커넥션이 순간적으로 끊긴 상태에서 `unlock()` 호출 자체가 네트워크 예외를 만날 수 있다.

두 경우 다 "본 로직의 성공/실패"와는 무관한, 순수하게 정리(cleanup) 단계의 문제인데, 정리
단계의 실패가 본 로직의 성공 여부를 덮어써 버리는 구조였다.

## 해결

`unlock()` 호출을 별도 try-catch로 감싸서, 실패하면 경고 로그만 남기고 예외를 삼키도록 고쳤다.

```java
// src/main/java/.../order/service/OrderService.java
try {
    return orderPaymentProcessor.pay(idempotencyKey, request);
} finally {
    if (lock.isHeldByCurrentThread()) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("주문 락 해제에 실패했습니다. leaseTime 이후 자동 만료됩니다. userId={}",
                    request.userId(), e);
        }
    }
}
```

`PointChargeService`에도 동일한 패턴을 적용했다. `lock.isHeldByCurrentThread()` 체크를 먼저
둔 것도 같은 맥락이다 — 이미 lease time이 지나 자동 해제된 락을 굳이 다시 해제하려 시도하지
않도록 방어한 것이다.

이렇게 예외를 삼켜도 안전한 이유는 `tryLock`에 이미 lease time을 지정해뒀기 때문이다.
`unlock()`이 정말로 실패하더라도, 최악의 경우 lease time(`LOCK_LEASE_SECONDS`)이 지나면
Redisson이 알아서 락을 해제한다. 즉 "수동 해제 실패"의 최종 안전망이 이미 설계에 들어있었고,
그 안전망을 믿고 예외를 삼켜도 락이 영원히 안 풀리는 사고는 나지 않는다.

## 검증

`OrderConcurrencyIntegrationTest`가 동시 주문 시나리오에서 성공/실패 건수와 최종 잔액을
검증하는데, 이 테스트가 정상적으로 통과한다는 것 자체가 락 해제 경로가 정상 응답을 방해하지
않는다는 근거가 된다. `./gradlew test`로 로컬에서 실행해 통과를 확인했다.

## 배운 점

정리(cleanup) 코드는 "본 로직이 이미 성공했다"는 사실을 절대 가려서는 안 된다는 원칙을 지켜야
한다. Java의 `try`-`finally`는 `finally`의 예외가 `try`의 정상 흐름(리턴값 포함)을 덮어쓴다는
걸 정확히 알고 있어야, 이런 종류의 버그를 코드 작성 시점에 미리 피할 수 있다. 그리고 정리 코드의
실패를 안전하게 삼켜도 되는지는 "그 정리가 실패했을 때 최종적으로 문제가 저절로 해소되는
안전망(여기서는 lease time)이 있는가"로 판단하면 된다.
