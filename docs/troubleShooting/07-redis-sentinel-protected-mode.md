# Redis Sentinel protected-mode — 설계는 맞았는데 기본 보안 설정이 전부를 막고 있었음

## 배경

Redis 자체의 단일 장애점(SPOF) 문제를 인프라 레벨에서 실증하기 위해(ADR-009), docker-compose에
Sentinel 토폴로지를 구성했다: Master 1대, Replica 2대, Sentinel 3대(쿼럼 2), 그리고 이
토폴로지와 같은 네트워크를 쓰는 컨테이너화된 `app` 서비스.

```yaml
redis-master:
  image: redis:7.2-alpine
redis-replica-1:
  image: redis:7.2-alpine
  command: redis-server --replicaof redis-master 6379
redis-sentinel-1:
  image: redis:7.2-alpine
  volumes:
    - ./redis/sentinel-1.conf:/etc/redis/sentinel.conf
  command: redis-sentinel /etc/redis/sentinel.conf
```

## 문제 상황

이 구성을 실제로 돌려보기 전, 코드/설정 리뷰 단계에서 Redis의 기본 동작 하나가 눈에 걸렸다.
`redis-master`, `redis-replica-*`, `redis-sentinel-*` 어디에도 `bind`나 `requirepass`를
설정하지 않은 상태였다. Redis는 기본값으로 `protected-mode yes`가 켜져 있는데, 이 모드는
"`bind` 설정이 없고, 비밀번호도 없는" 상태에서 **루프백(127.0.0.1, ::1)이 아닌 연결을 전부
거부**한다.

이 구성에서 컨테이너 간에 실제로 오가야 하는 연결을 나열해보면 전부 다 루프백이 아니다.

- Replica가 Master에 복제 연결을 맺는 것 (`redis-replica-1` → `redis-master`).
- Sentinel이 Master/Replica를 모니터링하는 것 (`redis-sentinel-*` → `redis-master`).
- 앱(Redisson)이 Sentinel에 접속해 현재 Master 주소를 물어보는 것 (`app` → `redis-sentinel-*`).

이 셋 모두 서로 다른 컨테이너 사이의 연결이라, docker 브리지 네트워크를 통해 도착한다 — Redis
입장에서는 이게 "루프백이 아닌 연결"이다. 기본값 그대로 뒀다면 위 세 가지 연결이 전부
거부돼서, 애초에 Sentinel 토폴로지 자체가 서로 못 붙었을 것이다. 설계(Master-Replica-Sentinel
구조, 쿼럼 계산, failover 설정)는 맞게 짰는데, 그 설계를 실행 가능하게 만드는 가장 기본적인
전제(컨테이너끼리 서로 접속은 가능해야 한다)가 기본 보안 설정 때문에 막혀 있었던 셈이다.

## 원인

Redis protected-mode는 "실수로 인증 없이 인터넷에 노출된 Redis 인스턴스가 악용되는 걸 막기
위한" 보호 장치다. `bind`도 없고 비밀번호도 없는 상태를 "설정을 깜빡한 위험한 상태"로 간주해서,
루프백 이외의 접근을 기본적으로 차단한다. 이건 프로덕션 보안 관점에서는 합리적인 기본값이지만,
"여러 컨테이너가 서로 신뢰하고 통신해야 하는 로컬 docker-compose 데모 환경"이라는 전제와는
정면으로 충돌한다. 이 프로젝트의 다른 Redis/Sentinel 관련 설정(리스너 이름, hostname resolve
등)은 모두 잘 챙겼지만, 이 기본 보안 설정 하나는 처음에 빠져 있었다.

## 해결

모든 Redis 계열 컨테이너에 `protected-mode no`를 명시했다.

```yaml
# docker/docker-compose.yml
redis-master:
  image: redis:7.2-alpine
  command: redis-server --protected-mode no

redis-replica-1:
  image: redis:7.2-alpine
  command: redis-server --replicaof redis-master 6379 --protected-mode no
```

Sentinel은 커맨드가 이미 `redis-sentinel /etc/redis/sentinel.conf`로 고정돼 있어서, 커맨드
라인 인자 대신 마운트하는 conf 파일 안에 같은 설정을 직접 넣었다.

```
# docker/redis/sentinel-1.conf
port 26379
protected-mode no
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel monitor mymaster redis-master 6379 2
...
```

기존에 있던 단일 Redis 서비스(`redis`, host `bootRun`용)도 같은 이유로 함께 껐다 — 도커
네트워킹 구현체에 따라 host의 published port 경유 접속도 컨테이너 입장에선 루프백으로 인식
안 될 수 있어서, 로컬 전용 구성이라는 전제 하에 일관되게 처리했다.

이건 인증을 아예 없애는 게 아니라(애초에 비밀번호를 안 쓰는 구성이었다), "루프백 여부로
접근을 판단하는" protected-mode의 판단 기준 자체를 끈 것이다. 인터넷에 노출되지 않는 로컬
docker-compose 네트워크 안에서만 쓰는 구성이라는 전제 위에서 내린 선택이고, 프로덕션이라면
`requirepass`/ACL/네트워크 격리 같은 별도의 보안 조치가 필요하다는 점을 ADR-009에도
명시해뒀다.

## 검증

이 문제는 실행해서 발견한 게 아니라 **실행 전 리뷰 단계에서 미리 잡은 것**이다. 이후 실제로
로컬에서 `docker compose up -d --build`로 전체 스택을 띄우고 `redis-master` 컨테이너를 강제
종료해, Sentinel이 실제로 Replica를 새 Master로 승격시키는 것까지 확인했다 (승격까지 체감상
약 10초, 앱 재연결까지 추가로 약 3~4초 — ADR-009 참고). 이 결과가 나왔다는 것 자체가 protected-mode
수정이 없었다면 애초에 불가능했을 결과라는 것도 같이 확인된 셈이다.

## 배운 점

인프라를 여러 컨테이너로 쪼개서 구성할 때는, 각 컴포넌트 개별의 기본 보안/네트워크 설정이
컴포넌트 사이의 통신 자체를 막을 수 있다는 걸 함께 검토해야 한다. 이런 종류의 문제는 코드
로직의 버그가 아니라서 유닛 테스트로는 절대 안 잡히고, 실제로 인프라를 띄워보기 전까지는
잘 드러나지 않는다 — 이번에는 운 좋게 실행 전 리뷰에서 발견했지만, 그렇지 못했다면 "설계는
다 맞는데 아무것도 안 붙는" 상태로 상당한 디버깅 시간을 썼을 가능성이 높다. 새로운 인프라
컴포넌트를 docker-compose에 추가할 때마다 "이 컴포넌트의 기본값이 로컬 네트워크 환경을
전제로 하고 있는가"를 체크리스트로 갖고 있는 게 좋겠다는 교훈을 얻었다.
