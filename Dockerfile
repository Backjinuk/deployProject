# 1) React build (frontend)
FROM node:20-alpine AS ui
WORKDIR /ui
# package*.json 먼저 복사 → 캐시 최적화
COPY src/main/front/package*.json ./
RUN npm ci
COPY deployProject/src/main/front/ ./
RUN npm run build   # 결과: /ui/build

# 2) Spring Boot build (Gradle)
FROM gradle:8.10.0-jdk21-alpine AS backend
WORKDIR /app
# Gradle 캐시 최적화를 위해 설정/의존 먼저 복사
COPY settings.gradle.kts ./
COPY build.gradle.kts ./
COPY gradle ./gradle
COPY gradlew ./
RUN chmod +x ./gradlew
RUN ./gradlew --version

# 소스 복사
COPY deployProject/src ./src

# React 빌드 산출물 정적 리소스로 합치기
# (Spring Boot는 src/main/resources/static 아래 파일을 정적 서빙)
RUN mkdir -p ./src/main/resources/static
COPY --from=ui /ui/build ./src/main/resources/static

# 테스트 스킵하고 패키징 (bootJar/bootWar 자동 감지)
RUN ./gradlew clean bootJar -x test || ./gradlew clean bootWar -x test

# 3) Runtime (JRE)
FROM eclipse-temurin:21-jre
WORKDIR /app
# build/libs 안에 jar 또는 war 하나만 있다고 가정
COPY --from=backend /app/build/libs/* app.jar
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

