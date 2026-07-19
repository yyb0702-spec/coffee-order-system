# k6 부하 테스트

`OrderConcurrencyIntegrationTest`(Testcontainers)가 코드 레벨 정합성을 검증한다면,
이 디렉터리의 스크립트는 실제로 기동 중인 서버를 대상으로 같은 시나리오를 네트워크
너머에서 재현해 응답 코드/처리량을 관찰하는 용도다 (ADR-007).

## 사전 준비

1. `docker/docker-compose.yml`로 로컬 MySQL/Redis/Kafka를 띄운다.
2. 애플리케이션을 최소 1개(단일 인스턴스 검증) 또는 2개 이상 다른 포트로(다수 인스턴스 검증) 기동한다.
3. [k6](https://k6.io/docs/get-started/installation/)를 설치한다.

## 실행

```bash
# 단일 인스턴스
k6 run -e BASE_URL=http://localhost:8080 k6/order-concurrency-test.js

# 다수 인스턴스 (VU마다 순환하며 서로 다른 인스턴스를 호출)
k6 run -e BASE_URLS=http://localhost:8080,http://localhost:8081 k6/order-concurrency-test.js

# 파라미터 조정 (기본값: VUS=10, USER_ID=9999, MENU_ID=1, INITIAL_CHARGE=10000)
k6 run -e BASE_URL=http://localhost:8080 -e VUS=20 -e MENU_ID=2 k6/order-concurrency-test.js
```

`MENU_ID`는 `docs/db-schema.sql` / `V2__seed_menu.sql` 시드 데이터에 존재하는 메뉴 ID로 지정해야 한다.

## 확인할 지표

- `order_success`: 성공(201) 건수. 기본 시나리오(10,000P/4,000P/동시 10건) 기준 2여야 한다.
- `order_insufficient_point`: 잔액 부족(409, INSUFFICIENT_POINT) 건수. 기본 시나리오 기준 8이어야 한다.
- `order_lock_not_acquired`: Redisson 락 획득 실패(409, ORDER_LOCK_NOT_ACQUIRED) 건수. 0에 가까워야 하며,
  값이 크게 나오면 락 대기/임대 시간(`OrderService`의 `LOCK_WAIT_SECONDS`/`LOCK_LEASE_SECONDS`) 조정이 필요하다는 신호다.
- `http_req_failed`: 임계값 1% 미만으로 설정. 5xx나 네트워크 오류가 섞이면 임계값 실패로 표시된다.

다수 인스턴스로 실행했을 때도 성공/실패 건수 합이 단일 인스턴스와 동일하게 유지되는지가
"다수 서버 환경에서도 데이터 정합성을 보장"하는 도전 요구사항의 실증 근거다.
