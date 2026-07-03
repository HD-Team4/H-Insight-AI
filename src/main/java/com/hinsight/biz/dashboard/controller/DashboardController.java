package com.hinsight.biz.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.hinsight.biz.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Tag(name = "dashboard-controller", description = "기업 대시보드 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/biz/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    // 대시보드 화면 (차트는 아래 /data 를 fetch 해서 그림)
    @GetMapping
    @Operation(summary = "기업 대시보드", description = "기업 로그인 성공 후 대시보드 페이지를 렌더링한다")
    public String dashboardPage() {
        return "biz/dashboard/dashboard";
    }


    @ResponseBody
    @GetMapping("/data")
    @Operation(summary = "대시보드 데이터 제공", description = "기간별 집계 데이터 (JSON). 프론트가 탭 전환 시 재요청")
    public JsonNode data(@RequestParam(defaultValue = "1m") String period) {
        return dashboardService.getDashboard(period);
    }
}
