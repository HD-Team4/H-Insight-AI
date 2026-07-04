package com.hinsight.live.config;

import java.security.Principal;

/**
 * 라이브 채팅 발신자.
 * 표시명(displayName)과 식별자(id)를 분리한다.
 * - id: 연결마다 유일(회원=userId 기반, 익명=UUID). "is-me" 판정·중복 구분의 기준.
 * - displayName: 화면에 보여줄 이름(회원명 / "게스트-XXXX"). 겹쳐도 무방.
 *
 * getName() 은 유일한 id 를 돌려주며, 이 값이 STOMP CONNECTED 프레임의
 * user-name 헤더로 클라이언트에 전달된다(클라이언트가 자기 id 를 알게 됨).
 */
public final class LiveChatPrincipal implements Principal {

    private final String id;
    private final String displayName;

    public LiveChatPrincipal(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    public String getName() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
