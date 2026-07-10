package com.hinsight.product.dao;

import com.hinsight.product.model.vo.ProductKeyword;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProductKeywordDao {

    List<ProductKeyword> findByProductId(Long productId);

    /** 전체 상품의 키워드 (ES 색인용 일괄 조회) */
    List<ProductKeyword> findAll();

    int insert(ProductKeyword productKeyword);

    int delete(Long keywordId);
}
