package com.hinsight.product.service;

import com.hinsight.product.dao.ProductDao;
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

}
