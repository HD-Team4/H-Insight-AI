package com.hinsight.order.service;

import com.hinsight.cart.dao.CartDao;
import com.hinsight.cart.model.vo.Cart;
import com.hinsight.exception.custom.cart.CartItemNotFoundException;
import com.hinsight.exception.custom.product.ProductNotFoundException;
import com.hinsight.order.dao.PurchaseHistoryDao;
import com.hinsight.order.model.dto.OrderDirectRequest;
import com.hinsight.order.model.dto.OrderResponse;
import com.hinsight.order.model.dto.PurchaseHistoryResponse;
import com.hinsight.order.model.vo.PurchaseHistory;
import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final PurchaseHistoryDao purchaseHistoryDao;
    private final ProductDao productDao;   // 상품 정보 + 가격 스냅샷
    private final CartDao cartDao;          // 장바구니 주문/비우기

    // === 바로주문 (상세페이지) ===
    @Transactional
    public OrderResponse orderDirect(Long userId, OrderDirectRequest request) {
        OrderLine line = new OrderLine(request.productId(), request.quantity());
        return createOrder(userId, List.of(line));
    }

    // === 장바구니 전체 주문 ===
    @Transactional
    public OrderResponse orderFromCart(Long userId) {
        List<Cart> cartItems = cartDao.findByUserId(userId);
        if (cartItems.isEmpty()) {
            throw new CartItemNotFoundException();   // 장바구니가 비어 주문할 게 없음
        }

        List<OrderLine> lines = cartItems.stream()
                .map(c -> new OrderLine(c.getProductId(), c.getQuantity()))
                .toList();

        OrderResponse response = createOrder(userId, lines);
        cartDao.deleteByUserId(userId);   // 주문 완료 → 장바구니 비우기
        return response;
    }

    // === 구매이력 조회 ===
    @Transactional(readOnly = true)
    public List<PurchaseHistoryResponse> getHistory(Long userId) {
        return purchaseHistoryDao.findByUserId(userId).stream()
                .map(h -> PurchaseHistoryResponse.of(h, findProduct(h.getProductId())))
                .toList();
    }

    // === 공통 코어: 상품별로 구매이력 쌓기 ===
    private OrderResponse createOrder(Long userId, List<OrderLine> lines) {
        List<PurchaseHistoryResponse> items = new ArrayList<>();
        for (OrderLine line : lines) {
            Product product = findProduct(line.productId());

            PurchaseHistory ph = new PurchaseHistory();
            ph.setUserId(userId);
            ph.setProductId(line.productId());
            ph.setQuantity(line.quantity());
            ph.setPrice(BigDecimal.valueOf(product.getPrice()));   // 주문 당시 가격 스냅샷

            purchaseHistoryDao.insert(ph);
            items.add(PurchaseHistoryResponse.of(ph, product));
        }
        return OrderResponse.of(items);
    }

    private Product findProduct(Long productId) {
        Product product = productDao.getProductById(productId);
        if (product == null) {
            throw new ProductNotFoundException();
        }
        return product;
    }

    // 주문할 상품 한 줄 (productId + quantity) - 두 흐름의 공통 표현
    private record OrderLine(Long productId, Integer quantity) {}
}