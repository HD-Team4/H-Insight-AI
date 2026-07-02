package com.hinsight.biz.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/biz/dashboard")
public class DashboardController {

    // 기업 로그인 성공 후 착지 지점(임시 랜딩). 대시보드 실제 내용은 별도 작업.
    @GetMapping
    public String dashboard() {
        return "biz/dashboard/dashboard";
    }
}
