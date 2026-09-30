// ============================================================================
// practice-mvc k6 load-test — committed artifact (scope §10.7)
// ============================================================================

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://localhost:8080';
const PRODUCTS_PATH = '/api/v1/products';
const READ_PATH = '/api/v1/products/{id}';

const VUS_DEFAULT = 200;
const DURATION_DEFAULT = '60s';
const RAMP_DEFAULT = '10s';
const ENTITY_COUNT_DEFAULT = 50;
const WRITE_RATIO_DEFAULT = 0.1;
const P95_THRESHOLD_MS = 500;
const ERROR_RATE_THRESHOLD = 0.01;
const CHECK_RATE_THRESHOLD = 0.95;

const TARGET_URL = __ENV.TARGET_URL || BASE_URL;
const VUS = parseInt(__ENV.K6_VUS || String(VUS_DEFAULT), 10);
const DURATION = __ENV.K6_DURATION || DURATION_DEFAULT;
const RAMP = __ENV.K6_RAMP || RAMP_DEFAULT;
const ENTITY_COUNT = parseInt(__ENV.K6_ENTITY_COUNT || String(ENTITY_COUNT_DEFAULT), 10);
const WRITE_RATIO = parseFloat(__ENV.K6_WRITE_RATIO || String(WRITE_RATIO_DEFAULT));

export const options = {
  stages: [
    { duration: RAMP, target: VUS },
    { duration: DURATION, target: VUS },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    http_req_duration: [`p(95)<${P95_THRESHOLD_MS}`],
    http_req_failed: [`rate<${ERROR_RATE_THRESHOLD}`],
    checks: [`rate>${CHECK_RATE_THRESHOLD}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export function setup() {
  const ids = [];
  const headers = { 'Content-Type': 'application/json' };
  for (let i = 0; i < ENTITY_COUNT; i++) {
    const payload = JSON.stringify({
      name: `seed-product-${i}`,
      price: 10.0 + (i % 50),
    });
    const res = http.post(`${TARGET_URL}${PRODUCTS_PATH}`, payload, { headers });
    if (res.status === 201) {
      try {
        const body = JSON.parse(res.body);
        if (body.id) {
          ids.push(body.id);
        }
      } catch (e) {
        // ignore parse errors in setup
      }
    }
  }
  return { ids: ids.length > 0 ? ids : ['00000000-0000-0000-0000-000000000000'] };
}

export default function (data) {
  const isWrite = Math.random() < WRITE_RATIO;
  const headers = { 'Content-Type': 'application/json' };

  if (isWrite) {
    const name = `prod-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
    const payload = JSON.stringify({
      name: name,
      price: 25.5,
    });
    const res = http.post(`${TARGET_URL}${PRODUCTS_PATH}`, payload, { headers });
    const ok = check(res, { 'create status 201': (r) => r.status === 201 });
    if (ok) {
      try {
        const body = JSON.parse(res.body);
        if (body.id) {
          const readRes = http.get(`${TARGET_URL}${READ_PATH.replace('{id}', body.id)}`);
          check(readRes, { 'read-back ok': (r) => r.status === 200 && r.body.includes(name) });
        }
      } catch (e) {
        // failed check
      }
    }
  } else {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const res = http.get(`${TARGET_URL}${READ_PATH.replace('{id}', id)}`);
    check(res, { 'read status 200': (r) => r.status === 200 });
  }
}

export function handleSummary(data) {
  const filename = __ENV.K6_SUMMARY_OUT || 'k6-summary.json';
  return {
    stdout: textSummary(data),
    [filename]: JSON.stringify(data, null, 2),
  };
}

function textSummary(data) {
  const m = data.metrics;
  const reqs = (m.http_reqs || {}).values || {};
  const dur = (m.http_req_duration || {}).values || {};
  const fail = (m.http_req_failed || {}).values || {};
  const checks = (m.checks || {}).values || {};
  const f2 = (v) => (v != null ? v.toFixed(2) : 'N/A');

  let out = '';
  out += '\n  === practice-mvc load run ===\n';
  out += `  Requests : ${reqs.count || 0} total @ ${f2(reqs.rate)} req/s\n`;
  out += `  Failed   : ${((fail.rate || 0) * 100).toFixed(2)}%\n`;
  out += `  Checks   : ${((checks.rate || 0) * 100).toFixed(2)}% passed\n`;
  out += `  Latency  : avg=${f2(dur.avg)}ms p50=${f2(dur.med)}ms`;
  out += ` p95=${f2(dur['p(95)'])}ms p99=${f2(dur['p(99)'])}ms max=${f2(dur.max)}ms\n`;
  return out;
}
