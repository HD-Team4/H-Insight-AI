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
 * 폭주 경로 게이트키퍼 — waiting-room.paths 에 등록된 경로 접근을 가로채
 * CDN 대기 페이지로 보내고, 대기를 마친(wr_token 검증) 요청만 통과시킨다.
 * 토큰은 완전 1회용: 입장 시 소모되고 통과권도 1회성이라, 나갔다가 다시 이 경로로 오면 새 번호표를 받는다.
 */
@Component
@RequiredArgsConstructor
public class WaitingRoomInterceptor implements HandlerInterceptor {

    /** 세션 attribute — 입장 직후 URL 정리(리다이렉트) 한 홉만 통과시키는 1회용 통과권 */
    public static final String SESSION_PASS_ONCE = "WAITING_ROOM_PASS_ONCE";

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

        // 입장 직후 wr_token 을 떼어낸 clean URL 로 온 요청 — 1회용 통과권을 소모하고 통과.
        // (통과권은 여기서 즉시 제거되므로, 이후 새로고침/재방문은 다시 대기열로 간다)
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_PASS_ONCE))) {
            session.removeAttribute(SESSION_PASS_ONCE);
            return true;
        }

        String cleanUrl = urlWithoutToken(request);

        // 대기를 마치고 돌아온 요청 — 토큰 검증(1회용 소모) 후 1회용 통과권 부여
        if (waitingRoomService.admit(request.getParameter(TOKEN_PARAM))) {
            request.getSession(true).setAttribute(SESSION_PASS_ONCE, Boolean.TRUE);
            response.sendRedirect(cleanUrl);   // 주소창에서 wr_token 제거
            return false;
        }

        // 대기 페이지로 우회. origin = 환경 키 — 대기 페이지(app.js ORIGIN_PRESETS)가 폴링/복귀 오리진 결정에 사용
        // (오리진 URL 을 그대로 쿼리에 실으면 CloudFront WAF 가 RFI 패턴으로 403 차단해 키로 넘긴다)
        response.sendRedirect(pageUrl
                + "?redirect=" + URLEncoder.encode(cleanUrl, StandardCharsets.UTF_8)
                + "&origin=" + originKey(request));
        return false;
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
