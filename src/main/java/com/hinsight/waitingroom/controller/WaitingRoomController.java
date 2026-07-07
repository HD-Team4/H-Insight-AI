package com.hinsight.waitingroom.controller;

import com.hinsight.waitingroom.dto.WaitingStatusResponse;
import com.hinsight.waitingroom.service.WaitingRoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "waiting-room-controller", description = "가상 대기열 API — CDN 대기 페이지가 폴링")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/waiting-room")
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    @Operation(summary = "대기열 상태 조회",
            description = "토큰이 없으면 새로 발급해 대기열에 등록한다. wait-seconds 경과 시 READY 반환")
    @GetMapping("/status")
    public WaitingStatusResponse status(@RequestParam(required = false) String token) {
        return waitingRoomService.status(token);
    }
}
