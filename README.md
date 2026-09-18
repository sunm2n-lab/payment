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

## CI

PR 생성·갱신과 main 푸시 시 GitHub Actions(`.github/workflows/ci.yml`)가 다음 순서로 검증한다.

1. `spotlessCheck` — 코드 포맷
2. `test` — 단위·통합 테스트

실패한 테스트는 PR 체크에 주석으로 표시되고, 실패 시 `build/reports/tests` 가 artifact로 올라간다.
