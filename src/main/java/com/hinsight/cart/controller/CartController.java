package com.hinsight.cart.controller;

import com.hinsight.cart.model.dto.CartAddRequest;
import com.hinsight.cart.model.dto.CartResponse;
import com.hinsight.cart.model.dto.CartUpdateRequest;
import com.hinsight.cart.service.CartService;
import com.hinsight.common.dto.ApiResponse;
import com.hinsight.security.userdetails.CustomerUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/cart")
public class CartController {

    private final CartService cartService;

    // === 페이지 렌더링 ===

    @GetMapping
    public String cartPage(@AuthenticationPrincipal CustomerUserDetails user, Model model) {
        model.addAttribute("cart", cartService.getCart(user.getUserId()));
        return "customer/cart/cart";
    }

    // === AJAX API (JSON 반환) ===

    @ResponseBody
    @PostMapping("/items")
    public ApiResponse<CartResponse> addItem(@AuthenticationPrincipal CustomerUserDetails user,
                                             @RequestBody CartAddRequest request) {
        return ApiResponse.ok(cartService.addItem(user.getUserId(), request));
    }

    @ResponseBody
    @PatchMapping("/items/{cartId}")
    public ApiResponse<CartResponse> updateQuantity(@AuthenticationPrincipal CustomerUserDetails user,
                                                    @PathVariable Long cartId,
                                                    @RequestBody CartUpdateRequest request) {
        return ApiResponse.ok(cartService.updateQuantity(user.getUserId(), cartId, request));
    }

    @ResponseBody
    @DeleteMapping("/items/{cartId}")
    public ApiResponse<CartResponse> removeItem(@AuthenticationPrincipal CustomerUserDetails user,
                                                @PathVariable Long cartId) {
        return ApiResponse.ok(cartService.removeItem(user.getUserId(), cartId));
    }
}
