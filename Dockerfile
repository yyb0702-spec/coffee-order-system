# 앱을 docker-compose 네트워크 안에 넣기 위한 이미지.
# Sentinel/Kafka 컨테이너들과 같은 네트워크에 있어야 서비스 이름(redis-sentinel-1, kafka 등)으로
# 서로를 찾을 수 있다 (docs/adr/ADR-009 참고). host에서 직접 ./gradlew bootRun 하는 경로와는
# 별개이며, 이 이미지는 docker-compose의 `app` 서비스 전용이다.

# ---- 빌드 스테이지 ----
# 범용 gradle 이미지 대신 프로젝트가 고정한 wrapper(Gradle 9.5.1, gradle/wrapper/gradle-wrapper.properties)를
# 그대로 사용한다. 버전 불일치로 인한 빌드 실패를 피하기 위함.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew

# 의존성 레이어를 소스 변경과 분리해 캐시 효율을 높인다.
RUN ./gradlew --version --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ---- 런타임 스테이지 ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
