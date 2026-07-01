package com.hinsight.cart.service;

import com.hinsight.cart.dao.CartDao;
import com.hinsight.cart.model.dto.CartAddRequest;
import com.hinsight.cart.model.dto.CartItemResponse;
import com.hinsight.cart.model.dto.CartResponse;
import com.hinsight.cart.model.dto.CartUpdateRequest;
import com.hinsight.cart.model.vo.Cart;
import com.hinsight.exception.custom.cart.CartItemNotFoundException;
import com.hinsight.exception.custom.product.ProductNotFoundException;
import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartDao cartDao;
    private final ProductDao productDao;   // 상품 정보/재고 확인에 재사용

    // 장바구니 조회 (Cart + Product 합쳐서 화면용 DTO로)
    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        List<CartItemResponse> items = cartDao.findByUserId(userId).stream()
                .map(cart -> CartItemResponse.of(cart, findProduct(cart.getProductId())))
                .toList();
        return CartResponse.of(items);
    }

    // 담기: 이미 있으면 수량 누적, 없으면 새로 insert
    @Transactional
    public CartResponse addItem(Long userId, CartAddRequest request) {
        findProduct(request.productId()); // 상품 존재 확인

        Cart existing = cartDao.findByUserIdAndProductId(userId, request.productId());
        int newQuantity = (existing == null ? 0 : existing.getQuantity()) + request.quantity();

        if (existing == null) {
            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setProductId(request.productId());
            cart.setQuantity(request.quantity());
            cartDao.insert(cart);
        } else {
            cartDao.updateQuantity(existing.getCartId(), newQuantity);
        }
        return getCart(userId);
    }

    // 수량 변경: 0 이하면 삭제
    @Transactional
    public CartResponse updateQuantity(Long userId, Long cartId, CartUpdateRequest request) {
        Cart cart = findOwnedCart(userId, cartId);

        if (request.quantity() <= 0) {
            cartDao.delete(cart.getCartId());
            return getCart(userId);
        }

        findProduct(cart.getProductId()); // 상품 존재 확인
        cartDao.updateQuantity(cart.getCartId(), request.quantity());
        return getCart(userId);
    }

    // 항목 삭제
    @Transactional
    public CartResponse removeItem(Long userId, Long cartId) {
        Cart cart = findOwnedCart(userId, cartId);
        cartDao.delete(cart.getCartId());
        return getCart(userId);
    }

    // --- 내부 헬퍼 ---

    private Product findProduct(Long productId) {
        Product product = productDao.getProductById(productId);
        if (product == null) {
            throw new ProductNotFoundException();
        }
        return product;
    }

    // 존재 + 내 소유인지 확인 (남의 장바구니 못 건드리게)
    private Cart findOwnedCart(Long userId, Long cartId) {
        Cart cart = cartDao.findById(cartId);
        if (cart == null || !cart.getUserId().equals(userId)) {
            throw new CartItemNotFoundException();
        }
        return cart;
    }
}