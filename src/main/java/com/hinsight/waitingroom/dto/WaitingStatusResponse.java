package com.hinsight.waitingroom.dto;

/**
 * 가상 대기열 상태 응답. CDN 대기 페이지(waiting-room/app.js)가 폴링으로 소비하는 계약.
 *
 * @param status     WAITING(대기 중) | READY(입장 가능)
 * @param position   내 대기 순번 (1부터)
 * @param ahead      내 앞에 남은 인원
 * @param etaSeconds 예상 대기 시간(초)
 * @param token      대기열 토큰 — 최초 폴링 시 발급, READY 후 wr_token 파라미터로 입장 검증에 사용
 */
public record WaitingStatusResponse(
        String status,
        long position,
        long ahead,
        long etaSeconds,
        String token
) {
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_READY = "READY";
}
