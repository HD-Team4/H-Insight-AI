package com.hinsight.live.model.dto;

public record LiveViewerResponse(
        Long liveSessionId,
        String status,
        long viewerCount
) {
}
