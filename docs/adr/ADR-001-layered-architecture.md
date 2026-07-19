# ADR-001 도메인 패키지 + 3계층 구조

## 상태와 결정일

Accepted. 결정일: 2026-07-19.

## 맥락과 문제

메뉴, 포인트, 주문, 인기 메뉴, 이벤트 발행/소비가 함께 있는 과제에서 HTTP 처리, 트랜잭션 경계, 저장소 접근이 뒤섞이면 동시성·정합성 검증 책임을 추적하기 어렵다. 패키지를 계층(controller/service/entity) 기준으로만 나누면 도메인 간 경계도 흐려진다.

## 결정 동인

- API 흐름과 트랜잭션 경계를 읽기 쉽게 유지한다.
- 도메인별로 무엇이 원천 데이터이고 무엇이 파생 데이터인지 패키지 구조에서부터 드러낸다.
- 아직 근거 없는 공통 추상화(Generic Service 등)를 피한다.

## 검토한 선택지

| 선택지 | 판단 |
| --- | --- |
| 도메인 패키지(`menu`/`point`/`order`/`ranking`/`event`) 안에 `controller`/`service`/`entity`/`dto`/`repository` | 채택. 도메인 경계와 계층 책임을 동시에 드러낸다. |
| 계층 우선 패키지(`controller`/`service`/`entity`를 최상위에 두고 그 안에 도메인별 클래스) | 제외. 도메인 하나를 이해하려면 여러 최상위 패키지를 오가야 한다. |
| 기능별 Generic Manager/CommonService | 제외. 책임을 숨기고 검증 대상을 추적하기 어렵다. |

## 결정과 이유

패키지는 도메인(`menu`, `point`, `order`, `ranking`, `event`)을 최상위로 두고, 그 안에 `controller`-`service`-`entity`-`dto`(-`repository`) 3계층 구조를 반복한다. `ranking`은 파생 데이터만 다루므로 `entity`가 없고, `event`는 REST로 노출되지 않으므로 `controller` 대신 `producer`/`consumer`를 둔다. `common` 패키지는 도메인에 속하지 않는 예외 처리, 멱등키, 설정을 모은다.

Controller는 HTTP 요청/응답/검증만 담당하고, Service는 트랜잭션·락·이벤트 발행 흐름을, Repository는 JPA 영속성과 락 조회를 담당한다.

## 결과와 단점

도메인 하나의 요청 흐름을 그 패키지 안에서 끝까지 따라갈 수 있다. 반면 도메인 간 공유 로직(예: `OrderPaymentProcessor`가 `menu`/`point` 패키지의 Repository를 직접 참조하는 것)은 패키지 경계를 넘나들며, 도메인이 늘어나면 크로스 패키지 의존성 관리가 필요해진다.

## 재검토 조건

- 하나의 Service가 여러 도메인 책임을 지속적으로 갖게 될 때.
- 도메인 간 의존성이 순환하거나 지나치게 얽힐 때, 별도 도메인 계층(예: 공용 인터페이스) 도입 근거가 생길 때.

## 관련 항목

- 설계: `docs/design-rationale.md`, `docs/domain-policy.md`.
- 실제 구현: `src/main/java/com/example/coffeeordersystem` 하위 패키지 구조.
- 대체·폐기 규칙: [ADR 운영 규칙](adr-readme.md)을 따른다.
