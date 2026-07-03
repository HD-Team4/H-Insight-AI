package com.hinsight.biz.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "dashboard-controller", description = "기업 대시보드 컨트롤러")
@Controller
@RequestMapping("/biz/dashboard")
public class DashboardController {

    // 기업 로그인 성공 후 착지 지점(임시 랜딩). 대시보드 실제 내용은 별도 작업.
    @Operation(summary = "기업 대시보드", description = "기업 로그인 성공 후 착지하는 대시보드 페이지를 렌더링한다")
    @GetMapping
    public String dashboard() {
        return "biz/dashboard/dashboard";
    }
}
