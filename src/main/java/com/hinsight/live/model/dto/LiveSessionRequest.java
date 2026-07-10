package com.hinsight.live.model.dto;

import java.time.LocalDateTime;

public record LiveSessionRequest(
        Long productId,
        String liveTitle,
        String videoUrl,
        String thumbnailUrl,
        String liveMessage,
        Long cacheVersion,
        String status,
        LocalDateTime startedAt
) {
}
