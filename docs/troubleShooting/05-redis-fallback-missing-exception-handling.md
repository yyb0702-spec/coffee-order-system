# 설계 문서(ADR)에 적은 대로 구현되지 않았던 Redis 장애 폴백

## 문제 상황

`ADR-004`(Redis ZSET 인기 메뉴 랭킹)는 처음부터 "Redis 장애 시 DB로 폴백한다"는 결정을
명시하고 있었다. 그런데 실제 `RankingService.getPopularMenus()`의 구현은 이랬다.

```java
// 처음 구현
public List<PopularMenuResponse> getPopularMenus() {
    List<Map.Entry<Long, Long>> ranked = readFromRedis();
    if (ranked.isEmpty()) {
        ranked = readFromDatabase();
    }
    return toResponses(ranked);
}
```

`readFromRedis()`의 결과가 "비어있는 경우"만 DB로 폴백하고 있었다. 문서에는 "장애 시 폴백"이라고
적어놓고, 코드는 "결과가 없을 때 폴백"만 구현한 것이다.

## 원인

두 상황은 겉보기엔 비슷해 보이지만 원인이 다르다.

- **결과가 비어있는 경우**: Redis 연결은 정상인데, 그 날짜 키에 아직 집계된 랭킹 데이터가 없는
  경우다 (예: 자정 직후, 아직 아무도 주문 안 한 새 날짜).
- **Redis 장애인 경우**: `readFromRedis()` 내부의 `redisTemplate.opsForZSet()...` 호출 자체가
  `DataAccessException`(Spring이 Redis 커넥션 실패 등을 감싸는 예외)을 던진다.

`if (ranked.isEmpty())`는 첫 번째 경우만 잡는다. 두 번째 경우는 예외가 메서드 밖으로 그대로
전파돼서, `GlobalExceptionHandler`의 일반 예외 처리에 걸려 결국 500 에러로 사용자에게
나갔을 것이다. ADR에 "장애 시 폴백한다"고 적어놓은 것과 실제 동작이 어긋나 있었다.

이 간극은 처음 구현할 때부터 있었고, 반복적인 코드 리뷰 과정에서 "이 코드가 정말 ADR에 적힌
대로 동작하는지" 하나하나 다시 짚어보다가 발견했다 — 실행해서 발견한 버그가 아니라, 문서와
코드를 나란히 놓고 대조하다가 찾은 간극이었다.

## 해결

`readFromRedis()` 호출을 try-catch로 감싸, 예외가 발생한 경우도 폴백 경로로 합류하도록
고쳤다.

```java
// src/main/java/.../ranking/service/RankingService.java
public List<PopularMenuResponse> getPopularMenus() {
    List<Map.Entry<Long, Long>> ranked;
    try {
        ranked = readFromRedis();
    } catch (DataAccessException e) {
        log.warn("Redis 조회에 실패해 DB 폴백 경로로 전환합니다.", e);
        ranked = List.of();
    }
    if (ranked.isEmpty()) {
        ranked = readFromDatabase();
    }
    return toResponses(ranked);
}
```

"결과 없음"과 "장애"를 별도로 구분해서 처리하지 않고, 둘 다 `ranked = List.of()`로 합류시켜
같은 폴백 경로(DB 조회)를 타도록 통일한 게 포인트다. 두 경우 다 최종적으로는 "Redis에서
신뢰할 수 있는 데이터를 못 얻었으니 DB를 본다"는 같은 대응이 맞기 때문이다.

## 검증

로컬에서 Redis 컨테이너를 강제로 내린 상태로 `GET /api/menus/popular`를 호출해, 500이 아니라
200(DB 기준 랭킹)으로 응답하는 것을 실제로 확인했다(README [로컬 실행 검증](../../README.md#로컬-실행-검증)
참고).

## 배운 점

설계 문서(ADR, 정책 문서 등)에 "이렇게 동작한다"고 적었다는 것과, 실제 코드가 그렇게 동작한다는
것은 서로 다른 사실이다. 문서를 작성하는 시점의 의도와 실제 구현이 갈라지는 지점은 대부분
"정상 경로"가 아니라 "예외/장애 경로"에서 생긴다 — 정상 케이스는 테스트하면서 자연스럽게 눈에
띄지만, 장애 케이스는 일부러 재현하지 않으면 코드 리뷰나 실제 장애 상황이 오기 전까지 드러나지
않는다. 그래서 문서에 장애 대응을 적었다면, 그 문서를 코드 리뷰 체크리스트로 삼아 "이 예외
케이스가 정말 처리돼 있는가"를 별도로 확인하는 과정이 필요하다.
