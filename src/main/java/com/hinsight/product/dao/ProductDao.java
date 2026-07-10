package com.hinsight.product.dao;


import com.hinsight.product.model.dto.ProductDetailDto;
import com.hinsight.product.model.dto.ProductSearchQuery;
import com.hinsight.product.model.vo.PriceRange;
import com.hinsight.product.model.vo.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductDao {

    List<Product> getAllProducts();
    Product getProductById(Long productId);
    ProductDetailDto getProductDetailById(Long productId);
    List<Product> search(ProductSearchQuery query);
    long countSearch(ProductSearchQuery query);
    List<Product> findByIds(List<Long> productIds);
    PriceRange getPriceRange();

    // 판매 개선 승인 반영 — 모두 biz_id 일치 시에만 반영(다른 기업 상품 보호)
    int updateProductName(@Param("productId") Long productId, @Param("bizId") Long bizId, @Param("productName") String productName);
    int updateDescription(@Param("productId") Long productId, @Param("bizId") Long bizId, @Param("description") String description);
    int promoteProduct(@Param("productId") Long productId, @Param("bizId") Long bizId);
    List<Long> findProductIdsByBiz(@Param("bizId") Long bizId);
    List<Product> findPromotedProducts(@Param("limit") int limit);
}
