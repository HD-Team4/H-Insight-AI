/**
 * H-Insight-AI 가상 대기열(waiting room) 클라이언트
 *
 * 페이지 진입 (게이트키퍼가 폭주 시 이 URL로 리다이렉트):
 *   https://<CDN>/waiting-room/index.html?redirect=/customer/products&origin=<앱오리진>&token=<대기열토큰>
 *   - redirect : 원래 가려던 경로. 같은 오리진 경로("/...")만 허용, 그 외는 "/"로 대체
 *   - origin   : (선택) 리다이렉트해 온 앱 환경 키 ("local" | "alb"). ORIGIN_PRESETS 에 있을 때만 적용
 *                (전체 URL 을 쿼리로 넘기면 CloudFront WAF 가 RFI 패턴으로 차단(403)하고
 *                 오픈 리다이렉트 위험도 있어 사전 정의된 키만 받는다)
 *   - token    : (선택) 게이트키퍼가 발급한 대기열 토큰. 없으면 첫 status 응답의 token을 저장해 사용
 *
 * status API 계약 (GET {apiBase}{statusPath}?token=...):
 *   {
 *     "status":     "WAITING" | "READY",
 *     "position":   123,        // 내 대기 순번 (1부터)
 *     "ahead":      122,        // 내 앞에 남은 인원
 *     "etaSeconds": 240,        // 예상 대기 시간(초)
 *     "token":      "...",      // (선택) 발급/갱신된 대기열 토큰
 *     "redirectUrl": "..."      // (선택, READY 시) 서버가 지정하는 입장 URL. 없으면 appOrigin + redirect 로 이동
 *   }
 *
 * READY 시 이동 URL에 wr_token 쿼리 파라미터로 토큰을 붙여 보내므로,
 * 앱 쪽 게이트키퍼 필터는 wr_token 으로 통과 여부를 검증하면 된다.
 */
(function () {
    "use strict";

    var CONFIG = {
        // status API 오리진. "" = 대기 페이지와 같은 오리진(CloudFront에 /api/* → ALB 동작 추가 전제).
        // 별도 오리진 사용 시 https 절대 URL 지정(http는 mixed content로 차단됨) + 해당 API에 CORS 필요.
        apiBase: "",
        statusPath: "/api/waiting-room/status",
        // READY 시 redirect 경로 앞에 붙일 앱 오리진. "" = 대기 페이지와 같은 오리진.
        appOrigin: "http://hf4-alb-hinsight-1491375634.ap-northeast-2.elb.amazonaws.com",
        // 폴링 간격: 3~5초 (base + 0~jitter 랜덤)
        pollBaseMs: 3000,
        pollJitterMs: 2000,
        // 연속 실패 시 간격 배수 (최대 pollMaxMs까지)
        pollBackoffFactor: 1.5,
        pollMaxMs: 15000,
        tokenStorageKey: "hf4-waiting-room-token"
    };

    // origin 쿼리 파라미터(환경 키) → 프리셋. 키가 여기 있으면 CONFIG를 덮어쓴다.
    // (localhost는 API 직접 폴링 가능. ALB는 http라 https 페이지에서 fetch 불가 →
    //  apiBase는 same-origin(CloudFront /api/* behavior) 유지, 복귀 오리진만 ALB로)
    var ORIGIN_PRESETS = {
        "local": {
            apiBase: "http://localhost:8080",
            appOrigin: "http://localhost:8080"
        },
        "alb": {
            apiBase: "",
            appOrigin: "http://hf4-alb-hinsight-1491375634.ap-northeast-2.elb.amazonaws.com"
        }
    };

    var el = {
        position: document.getElementById("queuePosition"),
        ahead: document.getElementById("queueAhead"),
        eta: document.getElementById("queueEta"),
        progress: document.getElementById("queueProgressBar"),
        progressWrap: document.querySelector(".queue-progress"),
        message: document.getElementById("queueMessage"),
        title: document.getElementById("waitingTitle")
    };

    // 대기 순번이 이 값을 넘으면 "혼잡" 문구, 이하이면 "곧 입장" 문구
    var BUSY_THRESHOLD = 100;

    var params = new URLSearchParams(window.location.search);
    var redirectPath = sanitizeRedirect(params.get("redirect"));
    var token = params.get("token") || sessionStorage.getItem(CONFIG.tokenStorageKey) || "";

    var originPreset = ORIGIN_PRESETS[params.get("origin")];
    if (originPreset) {
        CONFIG.apiBase = originPreset.apiBase;
        CONFIG.appOrigin = originPreset.appOrigin;
    }

    var initialAhead = null;   // 첫 응답의 ahead — 진행률 계산 기준
    var failCount = 0;
    var finished = false;
    var pollTimer = null;

    /** 오픈 리다이렉트 방지: 같은 오리진 경로만 허용 */
    function sanitizeRedirect(raw) {
        if (!raw || raw.charAt(0) !== "/") return "/";
        if (raw.charAt(1) === "/" || raw.charAt(1) === "\\") return "/";
        return raw;
    }

    function formatEta(seconds) {
        if (seconds == null || isNaN(seconds) || seconds < 0) return "–";
        if (seconds < 60) return "1분 미만";
        var minutes = Math.round(seconds / 60);
        if (minutes < 60) return "약 " + minutes + "분";
        return "약 " + Math.floor(minutes / 60) + "시간 " + (minutes % 60) + "분";
    }

    function setMessage(text, stateClass) {
        el.message.textContent = text;
        el.message.className = "waiting-message" + (stateClass ? " " + stateClass : "");
    }

    // 순번 구간별 안내 문구: 1~100 = 곧 입장, 101+ = 혼잡
    function renderWaitingText(position) {
        var busy = (typeof position === "number" && position > BUSY_THRESHOLD);
        if (el.title) {
            el.title.textContent = busy ? "지금 접속 중인 분이 많아요" : "잠시 후 입장입니다";
        }
        setMessage(busy ? "순서가 되면 자동으로 이동해요. 잠시만 기다려 주세요." : "대기해 주세요.");
    }

    function setProgress(percent) {
        var clamped = Math.max(0, Math.min(100, percent));
        el.progress.style.width = clamped + "%";
        el.progressWrap.setAttribute("aria-valuenow", String(Math.round(clamped)));
    }

    function render(data) {
        if (typeof data.position === "number") {
            el.position.textContent = data.position.toLocaleString("ko-KR");
        }
        if (typeof data.ahead === "number") {
            el.ahead.textContent = data.ahead.toLocaleString("ko-KR") + "명";
            if (initialAhead === null) initialAhead = Math.max(data.ahead, 1);
            // 입장 전에는 99%까지만 표시
            setProgress(Math.min(99, ((initialAhead - data.ahead) / initialAhead) * 100));
        }
        el.eta.textContent = formatEta(data.etaSeconds);
    }

    function enter(data) {
        finished = true;
        clearTimeout(pollTimer);
        setProgress(100);
        setMessage("순서가 되었어요! 입장하고 있어요…", "is-ready");

        var url;
        if (data.redirectUrl && /^(https?:\/\/|\/[^/\\])/.test(data.redirectUrl)) {
            url = data.redirectUrl;
        } else {
            url = CONFIG.appOrigin + redirectPath;
            if (token) {
                url += (redirectPath.indexOf("?") === -1 ? "?" : "&") + "wr_token=" + encodeURIComponent(token);
            }
        }
        // 대기 페이지가 히스토리에 남지 않도록 replace 사용
        window.location.replace(url);
    }

    function poll() {
        var url = CONFIG.apiBase + CONFIG.statusPath
            + (token ? "?token=" + encodeURIComponent(token) : "");

        fetch(url, { cache: "no-store" })
            .then(function (res) {
                if (!res.ok) throw new Error("status " + res.status);
                return res.json();
            })
            .then(function (data) {
                failCount = 0;
                if (data.token) {
                    token = data.token;
                    sessionStorage.setItem(CONFIG.tokenStorageKey, token);
                }
                render(data);
                if (data.status === "READY") {
                    enter(data);
                    return;
                }
                renderWaitingText(data.position);
                schedule();
            })
            .catch(function () {
                failCount += 1;
                setMessage("대기열 상태를 확인하지 못했어요. 잠시 후 다시 시도할게요.", "is-error");
                schedule();
            });
    }

    function schedule() {
        if (finished) return;
        var delay = CONFIG.pollBaseMs + Math.random() * CONFIG.pollJitterMs;
        if (failCount > 0) {
            delay = Math.min(delay * Math.pow(CONFIG.pollBackoffFactor, failCount), CONFIG.pollMaxMs);
        }
        pollTimer = setTimeout(poll, delay);
    }

    // 백그라운드 탭에서 돌아오면 바로 상태 갱신
    document.addEventListener("visibilitychange", function () {
        if (!document.hidden && !finished) {
            clearTimeout(pollTimer);
            poll();
        }
    });

    poll();
})();
