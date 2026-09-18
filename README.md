# payment

학습용 결제 모듈.

## 환경

- Java 17
- Spring Boot 3.5.16
- Gradle 8.14 (wrapper 포함)

## 실행

```bash
./gradlew bootRun
```

## 테스트

```bash
./gradlew test
```

## 코드 스타일

Spotless + google-java-format 을 사용한다.

```bash
./gradlew spotlessApply   # 포맷 자동 적용 (커밋 전)
./gradlew spotlessCheck   # 포맷 검사만 (CI와 동일)
```
