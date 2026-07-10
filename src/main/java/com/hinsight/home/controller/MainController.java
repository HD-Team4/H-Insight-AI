package com.hinsight.home.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Tag(name = "main-controller", description = "메인 컨트롤러")
@Controller
public class MainController {

  @Operation(summary = "메인 진입", description = "루트 경로 접근 시 상품목록 페이지로 리다이렉트한다")
  @GetMapping("/")
  public String home() {
    return "redirect:/customer/products";
  }
}
