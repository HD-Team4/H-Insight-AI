package com.hinsight.cart.dao;

import com.hinsight.cart.model.vo.Cart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CartDao {

    // 특정 유저의 장바구니 전체 조회
    List<Cart> findByUserId(Long userId);

    // 장바구니 항목 1건 조회 (수정/삭제 전 존재·소유 확인용)
    Cart findById(Long cartId);

    // 같은 유저가 같은 상품을 이미 담았는지 확인 (담기 시 중복 체크)
    Cart findByUserIdAndProductId(@Param("userId") Long userId,
                                  @Param("productId") Long productId);

    // 새 항목 담기
    void insert(Cart cart);

    // 수량 변경
    void updateQuantity(@Param("cartId") Long cartId,
                        @Param("quantity") Integer quantity);

    // 항목 삭제
    void delete(Long cartId);

}