/**
 * docs/order-policy.md / ADR-007의 동시성 시나리오를 실제 HTTP 부하로 재현한다.
 * Testcontainers 통합 테스트(OrderConcurrencyIntegrationTest)가 "코드 레벨 정확성"을
 * 검증한다면, 이 스크립트는 "실행 중인 서버(단일 또는 다수 인스턴스)"를 대상으로
 * 같은 시나리오가 실제 네트워크/부하 조건에서도 유지되는지를 확인한다.
 *
 * 기본 시나리오: 유저 1명, 초기 포인트 10,000P, 메뉴 가격 4,000P, 동시 주문 10건
 *   -> 기대: 성공 2건, 실패(주로 INSUFFICIENT_POINT) 8건.
 *
 * 사용법 (단일 인스턴스):
 *   k6 run -e BASE_URL=http://localhost:8080 k6/order-concurrency-test.js
 *
 * 사용법 (다수 인스턴스 앞에 LB가 없고, VU마다 인스턴스를 번갈아 호출하고 싶을 때):
 *   k6 run -e BASE_URLS=http://localhost:8080,http://localhost:8081 k6/order-concurrency-test.js
 *
 * 커스텀 파라미터:
 *   -e VUS=10 -e USER_ID=9999 -e MENU_ID=1 -e INITIAL_CHARGE=10000
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const BASE_URLS = (__ENV.BASE_URLS ? __ENV.BASE_URLS.split(',') : [BASE_URL]).map((u) => u.trim());

const VUS = Number(__ENV.VUS || 10);
const TEST_USER_ID = Number(__ENV.USER_ID || 9999);
const MENU_ID = Number(__ENV.MENU_ID || 1);
const INITIAL_CHARGE = Number(__ENV.INITIAL_CHARGE || 10000);

export const options = {
  scenarios: {
    concurrent_orders: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: VUS,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // 락/DB 경합으로 실패하는 것과 별개로, 성공/실패 모두 5xx 없이 명확한 비즈니스 응답이어야 한다.
    http_req_failed: ['rate<0.01'],
  },
};

const successCount = new Counter('order_success');
const insufficientPointCount = new Counter('order_insufficient_point');
const lockNotAcquiredCount = new Counter('order_lock_not_acquired');
const otherFailureCount = new Counter('order_other_failure');

function pickBaseUrl(vu) {
  return BASE_URLS[(vu - 1) % BASE_URLS.length];
}

export function setup() {
  const baseUrl = pickBaseUrl(1);
  const chargeRes = http.post(
    `${baseUrl}/api/points/charge`,
    JSON.stringify({ userId: TEST_USER_ID, amount: INITIAL_CHARGE }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': `k6-setup-charge-${Date.now()}-${Math.random()}`,
      },
    }
  );
  check(chargeRes, { '초기 충전 성공(200)': (r) => r.status === 200 });
  return { userId: TEST_USER_ID, menuId: MENU_ID };
}

export default function (data) {
  const baseUrl = pickBaseUrl(__VU);
  const idempotencyKey = `k6-order-${__VU}-${__ITER}-${Date.now()}`;

  const res = http.post(
    `${baseUrl}/api/orders`,
    JSON.stringify({ userId: data.userId, menuId: data.menuId }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
    }
  );

  if (res.status === 201) {
    successCount.add(1);
  } else if (res.status === 409 && res.body && res.body.indexOf('INSUFFICIENT_POINT') !== -1) {
    insufficientPointCount.add(1);
  } else if (res.status === 409 && res.body && res.body.indexOf('ORDER_LOCK_NOT_ACQUIRED') !== -1) {
    lockNotAcquiredCount.add(1);
  } else {
    otherFailureCount.add(1);
  }

  check(res, {
    '201(성공) 또는 409(잔액부족/락 경합)만 허용': (r) => r.status === 201 || r.status === 409,
  });
}

export function teardown(data) {
  console.log(
    `[order-concurrency-test] userId=${data.userId}, initialCharge=${INITIAL_CHARGE}, ` +
      `menuId=${data.menuId} -> 결과는 k6 요약의 order_success / order_insufficient_point 카운터를 확인.`
  );
}
