package com.hinsight.cart.controller;

import com.hinsight.cart.model.dto.CartAddRequest;
import com.hinsight.cart.model.dto.CartResponse;
import com.hinsight.cart.model.dto.CartUpdateRequest;
import com.hinsight.cart.service.CartService;
import com.hinsight.common.dto.ApiResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/cart")
public class CartController {

    private final CartService cartService;

    // 임시 개발용 userId. TODO: 로그인 기능 붙으면 세션에서 실제 userId 꺼내도록 교체
    private static final Long DEV_USER_ID = 1L;

    // === 페이지 렌더링 ===

    @GetMapping
    public String cartPage(Model model, HttpSession session) {
        model.addAttribute("cart", cartService.getCart(resolveUserId(session)));
        return "customer/cart/cart";
    }

    // === AJAX API (JSON 반환) ===

    @ResponseBody
    @PostMapping("/items")
    public ApiResponse<CartResponse> addItem(@RequestBody CartAddRequest request,
                                             HttpSession session) {
        return ApiResponse.ok(cartService.addItem(resolveUserId(session), request));
    }

    @ResponseBody
    @PatchMapping("/items/{cartId}")
    public ApiResponse<CartResponse> updateQuantity(@PathVariable Long cartId,
                                                    @RequestBody CartUpdateRequest request,
                                                    HttpSession session) {
        return ApiResponse.ok(cartService.updateQuantity(resolveUserId(session), cartId, request));
    }

    @ResponseBody
    @DeleteMapping("/items/{cartId}")
    public ApiResponse<CartResponse> removeItem(@PathVariable Long cartId,
                                                HttpSession session) {
        return ApiResponse.ok(cartService.removeItem(resolveUserId(session), cartId));
    }

    // === userId 해석 (교체 지점 한 곳) ===

    private Long resolveUserId(HttpSession session) {
        // TODO: 로그인 구현 후 → Long userId = (Long) session.getAttribute("userId");
        //       null 이면 로그인 페이지로 유도하도록 변경
        Object userId = session.getAttribute("userId");
        return (userId != null) ? (Long) userId : DEV_USER_ID;
    }
}