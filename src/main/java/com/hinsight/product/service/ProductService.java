package com.hinsight.product.service;

import com.hinsight.exception.custom.product.ProductNotFoundException;
import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.dto.ProductSearchConditionDto;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductDao productDao;

    public List<Product> getAllProducts() {
        return productDao.getAllProducts();
    }

    public Product getProductById(Long productId) {
        Product product = productDao.getProductById(productId);
        if (product == null) { //상품 관련 null exception 추가 , 추후 공통 예외처리
            throw new ProductNotFoundException();
        }
        return product;
    }

    public List<Product> searchProducts(ProductSearchConditionDto condition) {
        return productDao.search(condition);
    }
}
