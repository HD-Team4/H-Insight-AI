package com.hinsight.biz.report.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "report-controller", description = "리포트 컨트롤러")
@Controller
@RequestMapping("/biz/reports")
public class ReportController {
}
