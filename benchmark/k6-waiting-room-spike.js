import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

/**
 * 가상 대기열 오픈런 부하 테스트 — 동시 접속 스파이크(기본 500명, -e VUS 로 조절).
 * 별도 loadgen 서버에서 실행: k6 run -e BASE=http://<ALB-DNS> -e VUS=500 k6-waiting-room-spike.js
 *
 * 도메인 게이트(/customer/**) + 용량 기반 입장 아키텍처를 "실제 사용자 여정 그대로" 흉내낸다:
 *   1) /customer/loadtest 진입 (리다이렉트 수동 처리) — 경량 엔드포인트로 대기열만 테스트
 *        - 여유(동시 처리 inFlight<max & 대기 줄 없음) → 200 즉시 입장
 *        - 포화 → 302 로 CDN 대기 페이지로 밀림
 *   2) 302 면 대기열: 브라우저 app.js 처럼 status API 를 3~5초 간격 폴링
 *   3) READY 되면 wr_token 달고 원래 경로로 입장(302 → 통과권 → 실제 페이지 200)
 *   4) 입장 후 세션 통과권으로 다른 상품 둘러보기(재대기 없이 200 이어야)
 *
 * ⚠ k6 는 JS 미실행이라, 진입에서 302 를 자동으로 따라가면 CDN 정적 페이지만 받고 큐가 안 찬다.
 *   그래서 redirects:0 으로 302 를 직접 감지하고, status API 를 코드로 폴링해 실제 대기열을 만든다.
 */

// 대상 서버(ALB). loadgen 서버에선 -e BASE=http://... 로 오버라이드(파일 수정 불필요)
const BASE = __ENV.BASE || 'http://hf4-alb-hinsight-1491375634.ap-northeast-2.elb.amazonaws.com';
const ENTRY = `${BASE}/customer/loadtest`;        // 대기열 게이트 진입점(경량 — 무거운 상품페이지 대신)
const STATUS = `${BASE}/api/waiting-room/status`; // 대기열 상태 API (게이트 대상 아님)

// 관찰용 커스텀 지표
const directAdmit = new Counter('wr_direct_admit');           // 대기 없이 즉시 입장한 수
const queued = new Counter('wr_queued');                      // 대기열로 밀린 수
const admittedAfterWait = new Counter('wr_admitted_after_wait'); // 대기 후 입장한 수
const gaveUp = new Counter('wr_gave_up');                     // 시간 내 입장 실패
const timeToAdmit = new Trend('wr_time_to_admit_ms', true);   // 진입~입장까지 걸린 시간

// 규모/시간은 loadgen 서버에서 -e 로 조절(기본 500명 스파이크). RAMP 짧을수록 '급격'.
const VUS  = Number(__ENV.VUS  || 500);   // 동시 접속 목표
const RAMP = __ENV.RAMP || '15s';         // 급증 구간
const HOLD = __ENV.HOLD || '120s';        // 최대 부하 유지
const DOWN = __ENV.DOWN || '20s';         // 감소 구간

export const options = {
  // 응답 바디는 기본 폐기(메모리 절약). status 폴링만 바디를 유지(responseType:'text')
  discardResponseBodies: true,
  scenarios: {
    // 순간 0→N 연결 스파이크는 로드젠/콜드 ALB 가 못 받아 타임아웃난다(앱은 멀쩡).
    // RAMP 로 로드젠·ALB 가 함께 스케일되게 한다. 진짜 '순간 동시'가 필요하면 ALB 예열 + 분산 로드젠.
    openrun_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: VUS },   // 급증
        { duration: HOLD, target: VUS },   // 동시 유지
        { duration: DOWN, target: 0 },     // 감소
      ],
      gracefulRampDown: '20s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],                    // 302/폴링은 실패 아님. 5xx/타임아웃만
    http_req_duration: ['p(95)<3000', 'p(99)<10000'],  // 입장 요청은 2초 hold 로 느려짐 — 필요시 완화
  },
};

function jsonField(res, field) {
  try { return res.json(field); } catch (e) { return null; }
}

export default function () {
  const start = Date.now();

  // 1) 도메인 진입 — 리다이렉트 수동 처리로 "대기열로 갔는지" 판별
  const res = http.get(ENTRY, { redirects: 0, tags: { step: 'enter' } });

  if (res.status === 200) {
    // 여유 → 즉시 입장 (세션 통과권 쿠키가 응답에 실려 옴)
    directAdmit.add(1);
    timeToAdmit.add(Date.now() - start);
    browse();
    return;
  }
  if (res.status !== 302) {
    check(res, { 'entry는 200 또는 302': () => false });
    return;
  }

  // 2) 포화 → 대기열. Location 에서 원래 가려던 경로 복원
  queued.add(1);
  const loc = res.headers['Location'] || '';
  const m = loc.match(/[?&]redirect=([^&]+)/);
  const redirectPath = m ? decodeURIComponent(m[1]) : '/customer/loadtest';

  // 브라우저 app.js 처럼 status 폴링 (3~5초 지터)
  let token = null;
  let admitted = false;
  for (let i = 0; i < 70; i++) {
    const poll = http.get(token ? `${STATUS}?token=${token}` : STATUS,
      { responseType: 'text', tags: { step: 'poll' } });
    const t = jsonField(poll, 'token');
    if (t) token = t;
    if (jsonField(poll, 'status') === 'READY') { admitted = true; break; }
    sleep(3 + Math.random() * 2);
  }

  if (!admitted || !token) {
    gaveUp.add(1);
    return;
  }

  // 3) READY → wr_token 달고 원래 경로로 입장 (인터셉터가 통과권 부여 후 clean URL 로 302)
  const sep = redirectPath.includes('?') ? '&' : '?';
  const admit = http.get(`${BASE}${redirectPath}${sep}wr_token=${token}`,
    { redirects: 0, tags: { step: 'admit' } });
  check(admit, { '입장 302(통과권 부여)': (r) => r.status === 302 });
  if (admit.status === 302) {
    http.get(BASE + redirectPath, { tags: { step: 'landed' } });  // 통과권으로 실제 페이지
  }
  admittedAfterWait.add(1);
  timeToAdmit.add(Date.now() - start);
  browse();
}

/** 입장 후 세션 통과권으로 도메인 자유 이용 (재대기 없이 200 이어야) */
function browse() {
  for (let i = 0; i < 3; i++) {
    const r = http.get(`${BASE}/customer/loadtest`, { tags: { step: 'browse' } });
    check(r, { '둘러보기 200(통과권)': (x) => x.status === 200 });
    sleep(1);
  }
}
