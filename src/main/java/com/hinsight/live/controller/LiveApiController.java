package com.hinsight.live.controller;

import com.hinsight.common.dto.ApiResponse;
import com.hinsight.live.model.dto.LiveSessionRequest;
import com.hinsight.live.model.dto.LiveStatusRequest;
import com.hinsight.live.model.dto.LiveViewerResponse;
import com.hinsight.live.model.vo.LiveSession;
import com.hinsight.live.service.LiveSessionService;
import com.hinsight.live.service.LiveViewerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class LiveApiController {

    private final LiveSessionService liveSessionService;
    private final LiveViewerService liveViewerService;

    @GetMapping("/api/live/sessions")
    public ApiResponse<List<LiveSession>> sessions() {
        return ApiResponse.ok(liveSessionService.getLiveSessions());
    }

    @GetMapping("/api/live/sessions/{liveSessionId}")
    public ApiResponse<LiveSession> session(@PathVariable Long liveSessionId) {
        return ApiResponse.ok(liveSessionService.getLiveSession(liveSessionId));
    }

    @PostMapping("/api/live/sessions")
    public ApiResponse<LiveSession> create(@RequestBody LiveSessionRequest request) {
        return ApiResponse.ok(liveSessionService.create(request));
    }

    @PutMapping("/api/live/sessions/{liveSessionId}")
    public ApiResponse<LiveSession> update(@PathVariable Long liveSessionId,
                                           @RequestBody LiveSessionRequest request) {
        return ApiResponse.ok(liveSessionService.update(liveSessionId, request));
    }

    @PatchMapping("/api/live/sessions/{liveSessionId}/status")
    public ApiResponse<LiveSession> status(@PathVariable Long liveSessionId,
                                           @RequestBody LiveStatusRequest request) {
        return ApiResponse.ok(liveSessionService.updateStatus(liveSessionId, request.status()));
    }

    @DeleteMapping("/api/live/sessions/{liveSessionId}")
    public ApiResponse<Void> delete(@PathVariable Long liveSessionId) {
        liveSessionService.delete(liveSessionId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/live/{liveSessionId}/viewers/enter")
    public LiveViewerResponse enter(@PathVariable Long liveSessionId) {
        LiveSession session = liveSessionService.getLiveSession(liveSessionId);
        return new LiveViewerResponse(liveSessionId, session == null ? null : session.getStatus(),
                liveViewerService.enter(liveSessionId));
    }

    @PostMapping("/live/{liveSessionId}/viewers/leave")
    public LiveViewerResponse leave(@PathVariable Long liveSessionId) {
        LiveSession session = liveSessionService.getLiveSession(liveSessionId);
        return new LiveViewerResponse(liveSessionId, session == null ? null : session.getStatus(),
                liveViewerService.leave(liveSessionId));
    }

    @GetMapping("/live/{liveSessionId}/status")
    public LiveViewerResponse liveStatus(@PathVariable Long liveSessionId) {
        LiveSession session = liveSessionService.getLiveSession(liveSessionId);
        return new LiveViewerResponse(liveSessionId, session == null ? null : session.getStatus(),
                liveViewerService.count(liveSessionId));
    }
}
