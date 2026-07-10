package com.hinsight.live.controller;

import com.hinsight.common.dto.ApiResponse;
import com.hinsight.live.model.dto.LiveSessionRequest;
import com.hinsight.live.model.dto.LiveStatusRequest;
import com.hinsight.live.model.dto.LiveViewerResponse;
import com.hinsight.live.model.vo.LiveSession;
import com.hinsight.live.service.LiveSessionService;
import com.hinsight.live.service.LiveViewerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "live-api-controller", description = "라이브 방송 세션·시청자 API")
@RestController
@RequiredArgsConstructor
public class LiveApiController {

    private final LiveSessionService liveSessionService;
    private final LiveViewerService liveViewerService;

    @Operation(summary = "라이브 세션 목록", description = "전체 라이브 방송 세션 목록을 조회한다")
    @GetMapping("/api/live/sessions")
    public ApiResponse<List<LiveSession>> sessions() {
        return ApiResponse.ok(liveSessionService.getLiveSessions());
    }

    @Operation(summary = "라이브 세션 단건 조회", description = "세션 ID로 라이브 방송 세션을 조회한다")
    @GetMapping("/api/live/sessions/{liveSessionId}")
    public ApiResponse<LiveSession> session(@PathVariable Long liveSessionId) {
        return ApiResponse.ok(liveSessionService.getLiveSession(liveSessionId));
    }

    @Operation(summary = "라이브 세션 생성", description = "새 라이브 방송 세션을 생성한다")
    @PostMapping("/api/live/sessions")
    public ApiResponse<LiveSession> create(@RequestBody LiveSessionRequest request) {
        return ApiResponse.ok(liveSessionService.create(request));
    }

    @Operation(summary = "라이브 세션 수정", description = "기존 라이브 방송 세션 정보를 수정한다")
    @PutMapping("/api/live/sessions/{liveSessionId}")
    public ApiResponse<LiveSession> update(@PathVariable Long liveSessionId,
                                           @RequestBody LiveSessionRequest request) {
        return ApiResponse.ok(liveSessionService.update(liveSessionId, request));
    }

    @Operation(summary = "라이브 세션 상태 변경", description = "세션 상태(예정/방송중/종료 등)를 변경한다")
    @PatchMapping("/api/live/sessions/{liveSessionId}/status")
    public ApiResponse<LiveSession> status(@PathVariable Long liveSessionId,
                                           @RequestBody LiveStatusRequest request) {
        return ApiResponse.ok(liveSessionService.updateStatus(liveSessionId, request.status()));
    }

    @Operation(summary = "라이브 세션 삭제", description = "라이브 방송 세션을 삭제한다")
    @DeleteMapping("/api/live/sessions/{liveSessionId}")
    public ApiResponse<Void> delete(@PathVariable Long liveSessionId) {
        liveSessionService.delete(liveSessionId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "라이브 시청자 입장", description = "라이브 방송 시청자로 입장하고 현재 시청자 수를 반환한다")
    @PostMapping("/live/{liveSessionId}/viewers/enter")
    public LiveViewerResponse enter(@PathVariable Long liveSessionId) {
        LiveSession session = liveSessionService.getLiveSession(liveSessionId);
        return new LiveViewerResponse(liveSessionId, session == null ? null : session.getStatus(),
                liveViewerService.enter(liveSessionId));
    }

    @Operation(summary = "라이브 시청자 퇴장", description = "라이브 방송 시청을 종료하고 현재 시청자 수를 반환한다")
    @PostMapping("/live/{liveSessionId}/viewers/leave")
    public LiveViewerResponse leave(@PathVariable Long liveSessionId) {
        LiveSession session = liveSessionService.getLiveSession(liveSessionId);
        return new LiveViewerResponse(liveSessionId, session == null ? null : session.getStatus(),
                liveViewerService.leave(liveSessionId));
    }

    @Operation(summary = "라이브 시청 현황 조회", description = "라이브 방송 상태와 현재 시청자 수를 조회한다")
    @GetMapping("/live/{liveSessionId}/status")
    public LiveViewerResponse liveStatus(@PathVariable Long liveSessionId) {
        LiveSession session = liveSessionService.getLiveSession(liveSessionId);
        return new LiveViewerResponse(liveSessionId, session == null ? null : session.getStatus(),
                liveViewerService.count(liveSessionId));
    }
}
