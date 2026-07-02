package com.hinsight.order.controller;

import com.hinsight.common.dto.ApiResponse;
import com.hinsight.order.model.dto.OrderDirectRequest;
import com.hinsight.order.model.dto.OrderResponse;
import com.hinsight.order.model.dto.PurchaseHistoryResponse;
import com.hinsight.order.service.OrderService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/orders")
public class OrderController {

    private final OrderService orderService;

    // 임시 개발용 userId. TODO: 로그인 붙으면 세션에서 실제 userId 사용 (장바구니와 동일)
    private static final Long DEV_USER_ID = 1L;

    // === 구매이력 페이지 ===
    @GetMapping
    public String historyPage(Model model, HttpSession session) {
        model.addAttribute("history", orderService.getHistory(resolveUserId(session)));
        return "customer/order/checkout";
    }

    // === 바로주문 (상세페이지) ===
    @ResponseBody
    @PostMapping("/direct")
    public ApiResponse<OrderResponse> orderDirect(@RequestBody OrderDirectRequest request,
                                                  HttpSession session) {
        return ApiResponse.ok(orderService.orderDirect(resolveUserId(session), request));
    }

    // === 장바구니 전체 주문 ===
    @ResponseBody
    @PostMapping("/cart")
    public ApiResponse<OrderResponse> orderFromCart(HttpSession session) {
        return ApiResponse.ok(orderService.orderFromCart(resolveUserId(session)));
    }

    // === 구매이력 조회 (JSON, 테스트/AJAX용) ===
    @ResponseBody
    @GetMapping("/history")
    public ApiResponse<List<PurchaseHistoryResponse>> history(HttpSession session) {
        return ApiResponse.ok(orderService.getHistory(resolveUserId(session)));
    }

    private Long resolveUserId(HttpSession session) {
        Object userId = session.getAttribute("userId");
        return (userId != null) ? (Long) userId : DEV_USER_ID;
    }
}
