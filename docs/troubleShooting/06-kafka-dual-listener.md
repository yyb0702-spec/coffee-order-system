# Kafka advertised listener — host 프로세스와 컨테이너가 서로 다른 이름으로 서로를 봄

## 배경

로컬 인프라의 Kafka는 원래 `bitnami/kafka:3.7` 이미지를 쓰려고 했는데, Docker Hub API로 직접
확인해보니 이 이미지가 더 이상 무료로 배포되지 않는 상태였다("This image is no longer available
for free through Docker Hub..."). 그래서 Apache Kafka 프로젝트가 공식 배포하는
`apache/kafka:4.0.2`(KRaft 단일 노드 combined mode)로 전환했다. 이 시점에는 리스너를 하나만
설정했다.

```yaml
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
```

앱을 `./gradlew bootRun`으로 host에서 직접 실행하는 게 유일한 실행 방식이었을 때는 이걸로
충분했다.

## 문제 상황

이후 Redis Sentinel 인프라 가용성을 실증하기 위해 앱 자체를 컨테이너화했다(ADR-009). 이제
같은 Kafka 브로커에 두 종류의 클라이언트가 동시에 접속해야 하는 상황이 됐다.

- host에서 직접 실행하는 `./gradlew bootRun` 앱 (여전히 지원해야 하는 빠른 개발 경로).
- docker-compose 안에서 컨테이너로 뜨는 `app` 서비스 (Sentinel 실증용 전체 스택 경로).

`KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092` 하나만 있는 상태에서 컨테이너화된
`app`이 접속을 시도하면 문제가 생긴다.

## 원인

Kafka 클라이언트는 브로커에 처음 접속할 때 "advertised listener" 주소를 브로커로부터 안내받고,
이후 실제 프로�스/컨슘 요청은 그 안내받은 주소로 다시 접속한다. 이 프로젝트에서 advertised
listener를 `localhost:9092`로 고정해뒀다는 것은, Kafka가 모든 클라이언트에게 "나한테 다시
접속할 땐 `localhost:9092`로 오라"고 안내한다는 뜻이다.

- host의 `bootRun` 프로세스 입장에서는 `localhost`가 정확히 Kafka 컨테이너가 포트를 publish한
  그 주소이므로 문제없이 재접속된다.
- 하지만 컨테이너화된 `app` 입장에서는 `localhost`가 **자기 자신(app 컨테이너)**을 가리킨다.
  Kafka가 안내한 대로 `localhost:9092`에 재접속을 시도하면 자기 자신의 9092 포트를 찾게 되고,
  당연히 그런 서비스는 없으니 연결에 실패한다.

반대로 advertised listener를 컨테이너 네트워크의 서비스 이름(`kafka:9092`)으로 바꾸면, 이번엔
host의 `bootRun` 프로세스가 `kafka`라는 호스트명을 resolve하지 못해 실패한다. host의
네트워크 네임스페이스는 docker-compose가 만든 내부 DNS를 모르기 때문이다. 하나의 advertised
listener로는 두 접속 경로 중 하나가 항상 깨지는 구조였다 — 이 프로젝트에서 정확히 같은 종류의
문제를 Redis Sentinel에서도 다시 만났다([07번 문서](07-redis-sentinel-protected-mode.md) 참고,
다만 그건 다른 원인이다).

## 해결

리스너를 두 개로 나눴다: 컨테이너 간 통신용 INTERNAL과, host 프로세스용 EXTERNAL.

```yaml
# docker/docker-compose.yml
environment:
  KAFKA_NODE_ID: 1
  KAFKA_PROCESS_ROLES: broker,controller
  KAFKA_LISTENERS: INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093
  KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092
  KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
  KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
  KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
  KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
```

- **INTERNAL** (`kafka:29092`): 컨테이너화된 `app`이 사용한다. `app`도 같은 docker 네트워크
  안에 있으니 서비스 이름 `kafka`를 정상적으로 resolve할 수 있다.
- **EXTERNAL** (`localhost:9092`): host의 `bootRun` 프로세스가 그대로 사용한다. 기존 동작에
  영향 없음.

각 클라이언트는 자신이 접속한 리스너 이름에 대응하는 advertised 주소만 안내받으므로, 같은
브로커 하나로 두 접속 경로를 동시에 지원할 수 있다. `KAFKA_CONTROLLER_QUORUM_VOTERS`는
그대로 `localhost:9093`을 유지했다 — 이건 combined mode에서 controller가 브로커와 같은
컨테이너 안의 같은 프로세스이므로, 컨테이너 자기 자신을 가리키는 루프백 주소로도 항상
문제없이 resolve된다.

## 검증

host `bootRun` 경로는 로컬에서 `docker compose up -d mysql redis kafka` + `./gradlew bootRun`
조합으로 정상 동작과 `docker compose logs kafka`에 EXTERNAL 리스너 관련 에러가 없음을
확인했다. 컨테이너화된 `app` 경로(INTERNAL 리스너)는 DLT 재시도/재발행 테스트와 Sentinel
failover 테스트 과정에서 Kafka 이벤트 발행-소비가 정상적으로 이뤄지는 것으로 간접 확인했다
(README [로컬 실행 검증](../../README.md#로컬-실행-검증) 참고).

## 배운 점

같은 브로커/서버에 서로 다른 네트워크 네임스페이스(host vs 컨테이너)의 클라이언트가 동시에
접속해야 하는 상황이면, "advertised address" 하나로는 부족하고 리스너를 용도별로 분리해야
한다. 이 프로젝트는 이 패턴을 두 번 거쳤다 — 처음엔 Kafka 도입 시점에 (host 전용으로) 단순하게
시작했다가, 나중에 앱을 컨테이너화하면서 두 경로를 동시에 지원해야 하는 요구가 생겨 리스너를
분리했다. 처음부터 다중 리스너로 설계할 수도 있었지만, 그 시점엔 필요하지 않았던 복잡도를
미리 끌어오지 않고 실제로 필요해졌을 때 확장한 것이기도 하다.
