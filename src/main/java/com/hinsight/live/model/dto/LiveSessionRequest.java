package com.hinsight.live.model.dto;

import java.time.LocalDateTime;

public record LiveSessionRequest(
        Long productId,
        String status,
        LocalDateTime startedAt
) {
}
