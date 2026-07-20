# Spring AOP self-invocation으로 `@Transactional`이 조용히 무력화됨

## 문제 상황

Kafka ledger(`processed_event`) 테이블의 보존기간 정리를 위해 `ProcessedEventRetentionService`를
만들었다. 처음 구현은 스케줄링 메서드와 실제 삭제 로직 메서드를 분리한 구조였다.

```java
@Service
@RequiredArgsConstructor
public class ProcessedEventRetentionService {

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOldEntriesScheduled() {
        purgeOldEntries(); // 같은 클래스 안에서 this.purgeOldEntries() 호출
    }

    @Transactional
    public long purgeOldEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        return processedEventRepository.deleteByProcessedAtBefore(cutoff);
    }
}
```

컴파일도 되고, 실행도 되고, 매일 새벽 오래된 레코드도 실제로 지워진다. 겉으로 보면 아무 문제가
없어 보인다.

## 원인

Spring의 `@Transactional`은 AOP 프록시 기반으로 동작한다. Spring이 빈을 등록할 때 실제 객체를
감싸는 프록시(CGLIB 또는 JDK 동적 프록시)를 만들고, 외부에서 이 프록시를 통해 메서드를 호출할
때만 트랜잭션 시작/커밋/롤백 같은 부가 로직이 끼어든다.

문제는 `purgeOldEntriesScheduled()` 안에서 `purgeOldEntries()`를 호출하는 방식이다. 이건
`this.purgeOldEntries()`와 동일한, **같은 객체 인스턴스 안에서의 직접 호출**이다. 이 호출은
프록시를 거치지 않고 원본 객체의 메서드를 바로 실행하므로, `purgeOldEntries()`에 붙은
`@Transactional`은 사실상 아무 효과가 없다 — 마치 그 애노테이션이 없는 것처럼 동작한다.

이번 경우엔 `processedEventRepository.deleteByProcessedAtBefore(...)`가 Spring Data JPA의
쿼리 메서드라서, JPA 리포지토리 자체가 자기 완결적으로 한 번의 삭제 쿼리를 실행하는 구조였다.
그래서 트랜잭션이 없어도 삭제 자체는 정상적으로 일어났고, 실제 데이터 사고로 이어지지는
않았다. 하지만 만약 이 메서드 안에서 여러 테이블에 걸친 작업을 묶어야 했다면, `@Transactional`이
없는 채로 실행되면서 중간에 실패했을 때 일부만 반영되는 실제 데이터 정합성 문제로 이어졌을
것이다.

## 해결

두 메서드를 하나로 합쳐, `@Scheduled`와 `@Transactional`을 같은 메서드에 함께 붙였다.

```java
// src/main/java/.../event/admin/ProcessedEventRetentionService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessedEventRetentionService {

    private static final int RETENTION_DAYS = 30;
    private final ProcessedEventRepository processedEventRepository;

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public long purgeOldEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        long deleted = processedEventRepository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("보존기간({}일)이 지난 처리 이력 {}건을 정리했습니다.", RETENTION_DAYS, deleted);
        }
        return deleted;
    }
}
```

이렇게 하면 Spring의 `TaskScheduler`가 이 메서드를 호출할 때 **항상 프록시를 거쳐서** 외부에서
호출하는 형태가 된다 (스케줄러는 빈 컨테이너가 관리하는 프록시 빈을 호출하지, 내부 구현 객체를
직접 참조하지 않는다). 그래서 `@Transactional`이 self-invocation 문제 없이 정상적으로
적용된다.

## 검증

이 서비스는 cron이 새벽 3시 30분이라 스케줄 실행 자체를 기다려서 확인하기는 어렵고, 대신 수동
트리거용 엔드포인트(`POST /api/admin/processed-events/purge`)를 함께 만들어 언제든 같은
로직을 즉시 호출해 확인할 수 있게 해뒀다.

## 배운 점

같은 클래스 안에서의 메서드 호출은 Spring AOP 기반 기능(트랜잭션뿐 아니라 캐싱, 재시도,
비동기 실행 등 `@Transactional`/`@Cacheable`/`@Retryable`/`@Async` 전부)이 적용되지 않는다는
걸 항상 의식해야 한다. 이 문제를 피하는 방법은 여러 가지다 — 이번처럼 메서드를 합치거나, 다른
빈으로 분리해서 진짜 외부 호출로 만들거나, `AopContext.currentProxy()`로 프록시를 얻어
호출하는 방법도 있다. 어떤 방법을 쓰든, "이 메서드가 같은 클래스 안에서 호출되는가, 외부에서
호출되는가"를 항상 먼저 따져봐야 한다는 게 핵심이다.
