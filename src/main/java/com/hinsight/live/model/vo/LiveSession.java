package com.hinsight.live.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LiveSession {

    private Long liveSessionId;
    private Long productId;
    private String liveTitle;
    private String videoUrl;
    private String thumbnailUrl;
    private String liveMessage;
    private Long cacheVersion;
    private String status;
    private LocalDateTime startedAt;
}
