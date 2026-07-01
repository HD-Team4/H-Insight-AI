package com.hinsight.product.dao;

import com.hinsight.product.model.dto.ProductDetailDto;
import com.hinsight.product.model.dto.ProductSearchConditionDto;
import com.hinsight.product.model.vo.Product;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProductDao {

    List<Product> getAllProducts();
    ProductDetailDto getProductById(Long productId);
    List<Product> search(ProductSearchConditionDto condition);

}
