package com.hinsight.product.dao;


import com.hinsight.product.model.dto.ProductDetailDto;
import com.hinsight.product.model.dto.ProductSearchQuery;
import com.hinsight.product.model.vo.PriceRange;
import com.hinsight.product.model.vo.Product;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProductDao {

    List<Product> getAllProducts();
    Product getProductById(Long productId);
    ProductDetailDto getProductDetailById(Long productId);
    List<Product> search(ProductSearchQuery query);
    List<Product> findByIds(List<Long> productIds);
    PriceRange getPriceRange();
}
