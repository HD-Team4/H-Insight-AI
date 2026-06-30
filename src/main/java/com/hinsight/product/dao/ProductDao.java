package com.hinsight.product.dao;

import com.hinsight.product.model.vo.Product;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProductDao {

    List<Product> getAllProducts();
    Product getProductById(Long productId);

}
