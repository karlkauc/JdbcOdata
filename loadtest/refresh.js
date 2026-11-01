// 50 clients refresh one feed at the same time, like Excel on a Monday morning. BASE, FEED, LOGIN and CLIENTS come
// from run.sh. A 503 is a correct answer of the concurrency limit, so it counts separately from errors.
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import encoding from 'k6/encoding';

const ok = new Counter('answers_200');
const busy = new Counter('answers_503');
const failed = new Counter('answers_other');
const okDuration = new Trend('ok_duration', true);

export const options = {
  scenarios: {
    refresh: {
      executor: 'per-vu-iterations',
      vus: Number(__ENV.CLIENTS),
      iterations: 3,
      maxDuration: '10m',
    },
  },
  summaryTrendStats: ['min', 'med', 'p(95)', 'max'],
  discardResponseBodies: true,
};

const headers = { Authorization: `Basic ${encoding.b64encode(__ENV.LOGIN)}` };

export default function () {
  const r = http.get(`${__ENV.BASE}/odata/${__ENV.FEED}`, { headers, timeout: '300s' });
  (r.status === 200 ? ok : r.status === 503 ? busy : failed).add(1);
  if (r.status === 200) okDuration.add(r.timings.duration);
  check(r, { 'answered 200 or 503': (x) => x.status === 200 || x.status === 503 });
}

export function handleSummary(data) {
  return { [`/results/${__ENV.FEED}.json`]: JSON.stringify(data) };
}
