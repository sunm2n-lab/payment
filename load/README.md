# 부하 테스트

Phase 0 완료 기준의 부하 검증이다. 서로 다른 CARD 결제를 **생성 → 승인**하는 루프를 100 VU 로 1분간 돌린다.

## 준비

```bash
# k6 설치 (macOS)
brew install k6

# MySQL
docker compose up -d
```

## 실행

서버는 **`load` 프로파일**로 띄운다. 100 VU 에서 Hibernate SQL DEBUG 로깅은 처리량을 눈에 띄게 깎기 때문이다.

```bash
./gradlew bootRun --args='--spring.profiles.active=load'
```

다른 터미널에서:

```bash
k6 run -e API_KEY=mk_test_merchant_1 -e BASE_URL=http://localhost:8080 load/create-confirm.js
```

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `API_KEY` | (필수) | 시드 가맹점의 `X-API-Key`. 없으면 `setup()` 에서 실패한다 |
| `BASE_URL` | `http://localhost:8080` | 대상 서버 |

## 합격 기준

**실패율 0.** 이 부하는 정상 경로만 타므로 4xx 가 나올 이유가 없다. 4xx 도 결함(시드 키 오류, 검증 실패, 상태 불일치)이며 5xx·네트워크 오류·타임아웃과 함께 전부 실패로 센다.

```js
thresholds: {
  http_req_failed: ['rate==0'],   // 4xx·5xx·status 0 전부
  checks: ['rate==1'],
}
```

k6 는 `check` 실패만으로는 실패 종료하지 않으므로 반드시 `thresholds` 로 건다. threshold 를 위반하면 k6 가 0 이 아닌 코드로 끝난다.

## 왜 CARD 만 쓰는가

MONEY 의 공유 지갑 경쟁은 **S2(머니 잔액 동시 차감)** 의 주제다. Phase 0 의 부하는 "정상 케이스에서 동작하는지" 만 본다. VU 마다 고유한 `orderId` 를 만들어 결제끼리도 경쟁하지 않게 한다.
