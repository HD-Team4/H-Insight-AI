package com.hinsight.biz.dashboard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hinsight.biz.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor
@RequestMapping("/biz/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    // 대시보드 화면 (차트는 아래 /data 를 fetch 해서 그림)
    @GetMapping
    public String dashboardPage() {
        return "biz/dashboard/dashboard";
    }

    // 기간별 집계 데이터 (JSON). 프론트가 탭 전환 시 재요청.
    @ResponseBody
    @GetMapping("/data")
    public JsonNode data(@RequestParam(defaultValue = "1m") String period) {
        return dashboardService.getDashboard(period);
    }
}
