// ============================================================================
// petclinic k6 load-test — committed artifact (scope §10.7: lives outside the
// target tree; the agent cannot edit its own ruler).
//
// Hand-port of REF service/advanced/k6/template-form.js with the petclinic
// slots inlined as constants (build step 2: "profile as constants"). Never
// regenerated at runtime; any change to this file invalidates cached
// baselines because its sha is part of the baseline-cache key (scope §10.24).
//
// Fixed scenario standard (same as REF, for comparability):
//   setup()    seeds ENTITY_COUNT owners via the real create endpoint
//   default()  WRITE_RATIO creates, 1-WRITE_RATIO reads of seeded owners
//
// Form scenario: create POSTs application/x-www-form-urlencoded; the new id
// comes from the redirect Location header (ID_RE, group 1).
//
// Anti-gaming read-back (scope §10.28): every write in default() is
// immediately followed by a GET of the new id with a content assertion —
// "fast because writes stopped landing" fails the checks gate, not just the
// benchmark. Setup writes are NOT read-back-checked: a seeded id that fails
// to land already fails the read checks in default(), and check() inside
// setup() would pollute the checks metric before load starts. The gate is
// the checks rate, so the read-back lives exactly where the gaming would.
//
// Runner overrides:
//   TARGET_URL        booted target address (default: BASE_URL)
//   K6_SUMMARY_OUT    summary JSON path (default: k6-summary.json) — the
//                     evidence dir is bind-mounted into the k6 container so
//                     this lands on the host (scope §10.16)
// Smoke-gate overrides (validation run only):
//   K6_VUS / K6_DURATION / K6_RAMP / K6_ENTITY_COUNT / K6_WRITE_RATIO
// ============================================================================

import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = 'http://localhost:8080';
const CREATE_PATH = '/owners/new';
const READ_PATH = '/owners/{id}';
const CREATE_STATUS = 302;
const READ_STATUS = 200;
const ID_RE = /\/owners\/(\d+)/;

const FORM_FIELDS = {
  address: '1 Test St',
  city: 'Testville',
  firstName: 'k6-__UNIQ__',
  lastName: 'loadtest',
  telephone: '1234567890',
};

const VUS_DEFAULT = 200;
const DURATION_DEFAULT = '60s';
const RAMP_DEFAULT = '10s';
const ENTITY_COUNT_DEFAULT = 50;
const WRITE_RATIO_DEFAULT = 0.1;
const P95_THRESHOLD_MS = 500;
const ERROR_RATE_THRESHOLD = 0.01;
const CHECK_RATE_THRESHOLD = 0.95;

const TARGET_URL = __ENV.TARGET_URL || BASE_URL;
const VUS = parseInt(__ENV.K6_VUS || `${VUS_DEFAULT}`);
const DURATION = __ENV.K6_DURATION || DURATION_DEFAULT;
const RAMP = __ENV.K6_RAMP || RAMP_DEFAULT;
const ENTITY_COUNT = parseInt(__ENV.K6_ENTITY_COUNT || `${ENTITY_COUNT_DEFAULT}`);
const WRITE_RATIO = parseFloat(__ENV.K6_WRITE_RATIO || `${WRITE_RATIO_DEFAULT}`);

export const options = {
  stages: [
    { duration: RAMP, target: VUS },
    { duration: DURATION, target: VUS },
    { duration: '5s', target: 0 },
  ],
  thresholds: {
    // verdict annotation only (scope §6): at 200 VUs on 2 CPUs this implies
    // ~670 RPS — beyond the envelope's physics. k6 exit 99 = measured FAIL,
    // parsed like any other result.
    http_req_duration: [`p(95)<${P95_THRESHOLD_MS}`],
    http_req_failed: [`rate<${ERROR_RATE_THRESHOLD}`],
    checks: [`rate>${CHECK_RATE_THRESHOLD}`],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

let seedCounter = 0;
function uniq() {
  seedCounter += 1;
  return `${Date.now()}-${__VU}-${seedCounter}`;
}

function ownerForm() {
  // returns the encoded body plus the unique firstName value it embedded —
  // the read-back asserts that exact substring on the rendered owner page.
  const token = uniq();
  const parts = [];
  for (const [key, value] of Object.entries(FORM_FIELDS)) {
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value.replace(/__UNIQ__/g, token))}`);
  }
  return { body: parts.join('&'), name: `k6-${token}` };
}

function createOwner() {
  const form = ownerForm();
  // redirects: 0 — k6 auto-follows redirects by default, which would lose
  // the Location header the id is extracted from.
  const res = http.post(`${TARGET_URL}${CREATE_PATH}`, form.body, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    redirects: 0,
  });
  return { res, name: form.name };
}

export function setup() {
  const ids = [];
  for (let i = 0; i < ENTITY_COUNT; i++) {
    const created = createOwner();
    if (created.res.status === CREATE_STATUS) {
      const location = created.res.headers['Location'];
      const m = location ? location.match(ID_RE) : null;
      if (m) {
        ids.push(m[1]);
      } else {
        console.warn(`setup seed ${i}: no id match in Location header: ${location}`);
      }
    } else {
      console.warn(`setup seed ${i} failed: status=${created.res.status}`);
    }
  }

  if (ids.length === 0) {
    throw new Error(
      `Setup failed: 0/${ENTITY_COUNT} owners seeded ` +
      `(POST ${CREATE_PATH} expected ${CREATE_STATUS} with Location matching ${ID_RE}). ` +
      'Is the service running? Is the scenario valid for this repo?'
    );
  }

  console.log(`Seeded ${ids.length}/${ENTITY_COUNT} owners`);
  return { ids };
}

export default function (data) {
  if (Math.random() < WRITE_RATIO) {
    const created = createOwner();
    check(created.res, {
      'create status ok': (r) => r.status === CREATE_STATUS,
    });
    readBack(created);
  } else {
    const id = data.ids[Math.floor(Math.random() * data.ids.length)];
    const res = http.get(`${TARGET_URL}${READ_PATH.replace('{id}', id)}`);
    check(res, {
      'read status ok': (r) => r.status === READ_STATUS,
    });
  }
}

// Anti-gaming read-back (§10.28): the write must actually land — GET the new
// id and assert the submitted firstName appears in the response. The token
// is digits/dashes only, so no HTML-escaping can break the substring match
// on the Thymeleaf-rendered page. A create that 302s but never persisted, or
// that returns no id at all, fails a check here — never silently skipped.
function readBack(created) {
  const location = created.res.headers['Location'];
  const m = location ? location.match(ID_RE) : null;
  if (!m) {
    check(created.res, { 'create returned id': (r) => false });
    return;
  }
  const res = http.get(`${TARGET_URL}${READ_PATH.replace('{id}', m[1])}`);
  check(res, {
    'write landed (read-back)': (r) =>
      r.status === READ_STATUS && r.body != null && r.body.includes(created.name),
  });
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
  // k6 nests metric aggregates under .values (counter: count/rate, trend:
  // avg/med/p(...), rate: rate).
  const reqs = (m.http_reqs || {}).values || {};
  const dur = (m.http_req_duration || {}).values || {};
  const fail = (m.http_req_failed || {}).values || {};
  const checks = (m.checks || {}).values || {};
  const f2 = (v) => (v != null ? v.toFixed(2) : 'N/A');

  let out = '';
  out += '\n  === petclinic load run ===\n';
  out += `  Requests : ${reqs.count || 0} total @ ${f2(reqs.rate)} req/s\n`;
  out += `  Failed   : ${((fail.rate || 0) * 100).toFixed(2)}%\n`;
  out += `  Checks   : ${((checks.rate || 0) * 100).toFixed(2)}% passed\n`;
  out += `  Latency  : avg=${f2(dur.avg)}ms p50=${f2(dur.med)}ms`;
  out += ` p95=${f2(dur['p(95)'])}ms p99=${f2(dur['p(99)'])}ms max=${f2(dur.max)}ms\n`;
  return out;
}
