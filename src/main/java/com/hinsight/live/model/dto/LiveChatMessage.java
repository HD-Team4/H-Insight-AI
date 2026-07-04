package com.hinsight.live.model.dto;

// senderId: 발신자 유일 식별자(회원=u{userId}, 익명=g-{UUID}) — 클라이언트 "is-me" 판정용
// sender  : 화면 표시명(겹칠 수 있음)
public record LiveChatMessage(
        String senderId,
        String sender,
        String message,
        String type,
        long sentAt
) {
    public static LiveChatMessage chat(String senderId, String sender, String message) {
        return new LiveChatMessage(senderId, sender, message, "CHAT", System.currentTimeMillis());
    }

    /** 리뷰봇 자동 답변 메시지. senderId 는 시청자 id 와 겹치지 않는 고정값. */
    public static LiveChatMessage bot(String message) {
        return new LiveChatMessage("review-bot", "🤖 리뷰봇", message, "BOT", System.currentTimeMillis());
    }
}
