package com.hinsight.live.config;

import com.hinsight.security.userdetails.CustomerUserDetails;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP 연결마다 채팅 발신자(Principal)를 확정한다.
 * 표시명과 식별자를 분리해 {@link LiveChatPrincipal} 로 감싼다.
 * - 로그인 시청자: id="u{userId}"(유일), 표시명=회원 이름.
 * - 익명 시청자:   id="g-{UUID}"(연결마다 유일), 표시명="게스트-XXXX".
 * 표시명은 겹칠 수 있어도 id 는 유일하므로 "is-me" 오판이 없다.
 */
public class LiveChatHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                      WebSocketHandler wsHandler,
                                      Map<String, Object> attributes) {
        Principal principal = request.getPrincipal();
        if (principal instanceof Authentication auth
                && auth.isAuthenticated()
                && auth.getPrincipal() instanceof CustomerUserDetails details) {
            String displayName = details.getDisplayName();
            if (displayName == null || displayName.isBlank()) {
                displayName = details.getUsername(); // 이름이 비면 로그인 아이디로 대체
            }
            return new LiveChatPrincipal("u" + details.getUserId(), displayName);
        }

        // 익명: 유일 id 는 UUID, 표시명은 id 에서 파생한 4자리(연결 내내 고정, 겹쳐도 무해)
        String uuid = UUID.randomUUID().toString();
        String guestSuffix = String.format("%04d", Math.floorMod(uuid.hashCode(), 10000));
        return new LiveChatPrincipal("g-" + uuid, "게스트-" + guestSuffix);
    }
}
