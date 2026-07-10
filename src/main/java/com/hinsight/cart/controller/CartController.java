package com.hinsight.cart.controller;

import com.hinsight.cart.model.dto.CartAddRequest;
import com.hinsight.cart.model.dto.CartResponse;
import com.hinsight.cart.model.dto.CartUpdateRequest;
import com.hinsight.cart.service.CartService;
import com.hinsight.common.dto.ApiResponse;
import com.hinsight.security.userdetails.CustomerUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Tag(name = "cart-controller", description = "장바구니 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/cart")
public class CartController {

    private final CartService cartService;

    // === 페이지 렌더링 ===

    @Operation(summary = "장바구니 페이지", description = "로그인 사용자의 장바구니 페이지를 렌더링한다")
    @GetMapping
    public String cartPage(@AuthenticationPrincipal CustomerUserDetails user, Model model) {
        model.addAttribute("cart", cartService.getCart(user.getUserId()));
        return "customer/cart/cart";
    }

    // === AJAX API (JSON 반환) ===

    @Operation(summary = "장바구니 담기", description = "상품을 장바구니에 추가한다")
    @ResponseBody
    @PostMapping("/items")
    public ApiResponse<CartResponse> addItem(@AuthenticationPrincipal CustomerUserDetails user,
                                             @RequestBody CartAddRequest request) {
        return ApiResponse.ok(cartService.addItem(user.getUserId(), request));
    }

    @Operation(summary = "장바구니 수량 변경", description = "장바구니 항목의 수량을 변경한다")
    @ResponseBody
    @PatchMapping("/items/{cartId}")
    public ApiResponse<CartResponse> updateQuantity(@AuthenticationPrincipal CustomerUserDetails user,
                                                    @PathVariable Long cartId,
                                                    @RequestBody CartUpdateRequest request) {
        return ApiResponse.ok(cartService.updateQuantity(user.getUserId(), cartId, request));
    }

    @Operation(summary = "장바구니 항목 삭제", description = "장바구니에서 특정 항목을 제거한다")
    @ResponseBody
    @DeleteMapping("/items/{cartId}")
    public ApiResponse<CartResponse> removeItem(@AuthenticationPrincipal CustomerUserDetails user,
                                                @PathVariable Long cartId) {
        return ApiResponse.ok(cartService.removeItem(user.getUserId(), cartId));
    }
}
