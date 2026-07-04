package com.hinsight.live.controller;

import com.hinsight.ai.rag.LiveQuestionBotService;
import com.hinsight.live.config.LiveChatPrincipal;
import com.hinsight.live.model.dto.LiveChatMessage;
import com.hinsight.live.model.dto.LiveChatRequest;
import com.hinsight.live.service.LiveChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.security.Principal;
import java.util.List;

/**
 * 라이브 방 N:N 채팅.
 * - STOMP: 한 시청자가 보낸 메시지를 같은 방 구독자 전원에게 브로드캐스트하고 히스토리에 적재.
 * - REST : 늦게 입장한 시청자를 위한 최근 채팅 replay.
 */
@Controller
@RequiredArgsConstructor
public class LiveChatController {

    private static final int MAX_MESSAGE_LENGTH = 500;

    private final LiveChatService liveChatService;
    private final LiveQuestionBotService liveQuestionBotService;

    /** 클라이언트 SEND /app/live/{id}/chat → 방 구독자(/topic/live/{id}) 전원에게 전달. */
    @MessageMapping("/live/{liveSessionId}/chat")
    @SendTo("/topic/live/{liveSessionId}")
    public LiveChatMessage chat(@DestinationVariable Long liveSessionId,
                                @Payload LiveChatRequest request,
                                Principal principal) {
        String body = request == null ? null : request.message();
        if (body == null || body.isBlank()) {
            return null; // 빈 메시지는 브로드캐스트하지 않음
        }
        body = body.strip();
        if (body.length() > MAX_MESSAGE_LENGTH) {
            body = body.substring(0, MAX_MESSAGE_LENGTH);
        }

        String senderId;
        String sender;
        if (principal instanceof LiveChatPrincipal p) {
            senderId = p.getName();          // 유일 id
            // 익명은 방별 순번(게스트N)으로, 회원은 회원명으로 표시
            sender = p.isGuest()
                    ? liveChatService.resolveGuestName(liveSessionId, senderId)
                    : p.getDisplayName();
        } else if (principal != null) {
            senderId = principal.getName();
            sender = principal.getName();
        } else {
            senderId = "anonymous";
            sender = "게스트";
        }

        LiveChatMessage message = LiveChatMessage.chat(senderId, sender, body);
        liveChatService.save(liveSessionId, message);

        // 자주 묻는 질문 탐지 → 리뷰봇 자동 답변(별도 스레드, 실패해도 채팅엔 영향 없음)
        liveQuestionBotService.onUserMessage(liveSessionId, body);

        return message;
    }

    /** 입장 직후 최근 채팅을 불러와 화면에 먼저 뿌린다(오래된 순). */
    @GetMapping("/live/{liveSessionId}/chat/history")
    @ResponseBody
    public List<LiveChatMessage> history(@PathVariable Long liveSessionId) {
        return liveChatService.recent(liveSessionId);
    }
}
