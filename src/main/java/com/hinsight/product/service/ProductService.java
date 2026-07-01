package com.hinsight.product.service;

import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.dto.ProductSearchConditionDto;
import com.hinsight.product.model.dto.ProductSearchQuery;
import com.hinsight.product.model.vo.CategoryGroup;
import com.hinsight.product.model.vo.PriceRange;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductDao productDao;
    private final CategoryService categoryService;

    public List<Product> getAllProducts() {
        return productDao.getAllProducts();
    }

    public Product getProductById(Long productId) {
        return productDao.getProductById(productId);
    }

    public List<Product> searchProducts(ProductSearchConditionDto condition) {
        List<Long> categoryIds = resolveCategoryIds(condition);

        ProductSearchQuery query = new ProductSearchQuery(
                condition.keyword(),
                categoryIds,
                condition.minPrice(),
                condition.maxPrice()
        );

        return productDao.search(query);
    }

    private List<Long> resolveCategoryIds(ProductSearchConditionDto condition) {
        if (condition.categoryId() != null) {
            return List.of(condition.categoryId());
        }

        if (condition.categoryGroup() != null && !condition.categoryGroup().isBlank()) {
            CategoryGroup group = CategoryGroup.valueOf(condition.categoryGroup());
            return categoryService.getCategoryIdsByGroup(group);
        }

        return null; // 전체 상품 대상
    }

    public PriceRange getPriceRange() {
        return productDao.getPriceRange();
    }

}

