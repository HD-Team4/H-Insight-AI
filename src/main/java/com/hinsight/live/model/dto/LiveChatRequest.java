package com.hinsight.live.model.dto;

/** 클라이언트가 STOMP 로 보내는 채팅 입력(본문만). 발신자명은 서버가 연결의 Principal 로 결정 */
public record LiveChatRequest(String message) {
}
