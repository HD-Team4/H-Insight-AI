package com.hinsight.waitingroom.interceptor;

import com.hinsight.waitingroom.service.WaitingRoomService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 도메인 게이트키퍼 — waiting-room.paths(기본 /customer/**) 접근을 가로챈다.
 * 평상시엔 그냥 통과시키고, 인스턴스가 포화(CPU≥target)돼 대기 줄이 생기면 그때부터 새 유입을
 * CDN 대기 페이지로 보낸다. 한 번 입장한 사용자는 세션 통과권으로 사이트 전체를 대기 없이 계속 이용한다.
 */
@Component
@RequiredArgsConstructor
public class WaitingRoomInterceptor implements HandlerInterceptor {

    /** 세션 attribute — 지속 통과권. 입장 후 세션 동안 도메인 전체를 대기 없이 이용 */
    public static final String SESSION_PASSED = "WAITING_ROOM_PASSED";

    /** 입장 검증용 쿼리 파라미터 (waiting-room/app.js 가 READY 시 붙여서 돌아온다) */
    public static final String TOKEN_PARAM = "wr_token";

    private final WaitingRoomService waitingRoomService;

    @Value("${waiting-room.enabled:true}")
    private boolean enabled;

    @Value("${waiting-room.page-url}")
    private String pageUrl;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!enabled) return true;

        // 이미 입장한 사용자 → 도메인 전체 통과(다른 상품·장바구니 등 자유 이용). 처리 수 집계에 포함.
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_PASSED))) {
            waitingRoomService.enter();
            return true;
        }

        String cleanUrl = urlWithoutToken(request);

        // 대기를 마치고 wr_token 들고 돌아온 요청 — 토큰 검증 후 지속 통과권 부여(리다이렉트라 집계 안 함)
        if (waitingRoomService.admit(request.getParameter(TOKEN_PARAM))) {
            request.getSession(true).setAttribute(SESSION_PASSED, Boolean.TRUE);
            response.sendRedirect(cleanUrl);   // 주소창에서 wr_token 제거
            return false;
        }

        // 여유 있으면(동시 처리 여유 & 대기 줄 없음) 대기 없이 바로 입장 + 지속 통과권
        // tryAdmitDirect 가 슬롯을 원자적으로 예약(+1)하므로 enter() 를 또 부르지 않는다.
        if (waitingRoomService.tryAdmitDirect()) {
            request.getSession(true).setAttribute(SESSION_PASSED, Boolean.TRUE);
            waitingRoomService.holdConnectionForTest();   // (테스트 전용) 입장 요청이 DB 커넥션을 test-hold-ms 동안 점유 → 게이트가 실제로 참
            return true;
        }

        // 포화 → 대기 페이지로 우회. origin = 환경 키 (URL 을 그대로 실으면 CloudFront WAF 가 403)
        response.sendRedirect(pageUrl
                + "?redirect=" + URLEncoder.encode(cleanUrl, StandardCharsets.UTF_8)
                + "&origin=" + originKey(request));
        return false;
    }

    /** 게이트를 통과한(preHandle=true) 요청의 처리 종료 시 in-flight 카운트 감소 */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        waitingRoomService.exit();
    }

    /** 원 요청 URL에서 wr_token 만 제거한 경로+쿼리 (만료 토큰이 redirect 파라미터에 섞여 도는 것 방지) */
    private String urlWithoutToken(HttpServletRequest request) {
        return UriComponentsBuilder.fromPath(request.getRequestURI())
                .query(request.getQueryString())
                .replaceQueryParam(TOKEN_PARAM)
                .build(true)
                .toUriString();
    }

    /** 대기 페이지에 넘길 환경 키 — app.js 의 ORIGIN_PRESETS 키와 맞춰야 한다 */
    private String originKey(HttpServletRequest request) {
        String host = request.getServerName();
        return ("localhost".equals(host) || "127.0.0.1".equals(host)) ? "local" : "alb";
    }
}
