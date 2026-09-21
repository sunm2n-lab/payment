import http from 'k6/http';
import { check } from 'k6';

// Phase 0 완료 기준 부하: 서로 다른 CARD 결제를 생성하고 승인하는 루프.
// MONEY 의 공유 지갑 경쟁은 S2 의 주제이므로 여기서는 다루지 않는다.

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY;
const AMOUNT = 10000;

export const options = {
  vus: 100,
  duration: '1m',
  thresholds: {
    // 이 부하는 정상 경로만 탄다. 4xx 가 나올 이유가 없으므로 4xx 도 실패로 센다.
    // 기본 http_req_failed 는 4xx, 5xx, status 0(네트워크 오류·타임아웃)을 모두 실패로 집계한다.
    http_req_failed: ['rate==0'],
    // check 실패만으로는 k6 가 실패 종료하지 않으므로 반드시 threshold 로 건다.
    checks: ['rate==1'],
  },
};

export function setup() {
  if (!API_KEY) {
    throw new Error(
      'API_KEY 환경변수가 필요하다. 예: k6 run -e API_KEY=mk_test_merchant_1 load/create-confirm.js',
    );
  }
}

export default function () {
  const headers = { 'Content-Type': 'application/json', 'X-API-Key': API_KEY };
  // VU 마다 고유한 orderId 를 만들어 결제끼리 경쟁하지 않게 한다.
  const orderId = `k6-${__VU}-${__ITER}`;

  const createRes = http.post(
    `${BASE_URL}/v1/payments`,
    JSON.stringify({ orderId, amount: AMOUNT, method: 'CARD' }),
    { headers, tags: { name: 'create' } },
  );

  const created = check(createRes, {
    'create 200': (r) => r.status === 200,
    'paymentKey 발급': (r) => !!r.json('paymentKey'),
  });
  if (!created) {
    return;
  }

  const confirmRes = http.post(
    `${BASE_URL}/v1/payments/confirm`,
    JSON.stringify({ paymentKey: createRes.json('paymentKey'), orderId, amount: AMOUNT }),
    { headers, tags: { name: 'confirm' } },
  );

  check(confirmRes, {
    'confirm 200': (r) => r.status === 200,
    'status DONE': (r) => r.json('status') === 'DONE',
  });
}
