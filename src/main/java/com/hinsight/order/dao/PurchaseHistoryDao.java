package com.hinsight.order.dao;

import com.hinsight.order.model.vo.PurchaseHistory;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PurchaseHistoryDao {

    // 구매이력 1건 저장 (주문 시 상품 1개당 1행)
    void insert(PurchaseHistory purchaseHistory);

    // 특정 유저의 구매이력 전체 조회
    List<PurchaseHistory> findByUserId(Long userId);

}
