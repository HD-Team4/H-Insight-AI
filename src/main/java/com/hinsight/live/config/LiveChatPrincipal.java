package com.hinsight.live.config;

import java.security.Principal;

/**
 * 라이브 채팅 발신자. 식별자(id)와 표시명을 분리한다.
 * - id: 연결마다 유일(회원=u{userId}, 익명=g-{UUID}). "is-me" 판정 기준. getName() 이 반환.
 * - guest: 익명이면 true. 표시명은 방별 순번(게스트N)으로 컨트롤러에서 배정하므로 여기선 비워둔다.
 * - displayName: 회원의 표시명(회원 이름). 익명은 null.
 */
public final class LiveChatPrincipal implements Principal {

    private final String id;
    private final boolean guest;
    private final String displayName;

    public LiveChatPrincipal(String id, boolean guest, String displayName) {
        this.id = id;
        this.guest = guest;
        this.displayName = displayName;
    }

    @Override
    public String getName() {
        return id;
    }

    public boolean isGuest() {
        return guest;
    }

    public String getDisplayName() {
        return displayName;
    }
}
