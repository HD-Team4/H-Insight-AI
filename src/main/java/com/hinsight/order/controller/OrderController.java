package com.hinsight.order.controller;

import com.hinsight.common.dto.ApiResponse;
import com.hinsight.order.model.dto.OrderDirectRequest;
import com.hinsight.order.model.dto.OrderResponse;
import com.hinsight.order.model.dto.PurchaseHistoryResponse;
import com.hinsight.order.service.OrderService;
import com.hinsight.security.userdetails.CustomerUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/orders")
public class OrderController {

    private final OrderService orderService;

    // === 구매이력 페이지 ===
    @GetMapping
    public String historyPage(@AuthenticationPrincipal CustomerUserDetails user, Model model) {
        model.addAttribute("history", orderService.getHistory(user.getUserId()));
        return "customer/order/checkout";
    }

    // === 바로주문 (상세페이지) ===
    @ResponseBody
    @PostMapping("/direct")
    public ApiResponse<OrderResponse> orderDirect(@AuthenticationPrincipal CustomerUserDetails user,
                                                  @RequestBody OrderDirectRequest request) {
        return ApiResponse.ok(orderService.orderDirect(user.getUserId(), request));
    }

    // === 장바구니 전체 주문 ===
    @ResponseBody
    @PostMapping("/cart")
    public ApiResponse<OrderResponse> orderFromCart(@AuthenticationPrincipal CustomerUserDetails user) {
        return ApiResponse.ok(orderService.orderFromCart(user.getUserId()));
    }

    // === 구매이력 조회 (JSON, 테스트/AJAX용) ===
    @ResponseBody
    @GetMapping("/history")
    public ApiResponse<List<PurchaseHistoryResponse>> history(@AuthenticationPrincipal CustomerUserDetails user) {
        return ApiResponse.ok(orderService.getHistory(user.getUserId()));
    }
}
