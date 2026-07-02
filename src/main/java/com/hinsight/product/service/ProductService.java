package com.hinsight.product.service;

import com.hinsight.exception.custom.product.ProductNotFoundException;
import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.dto.ProductDetailDto;
import com.hinsight.product.model.dto.ProductSearchCondition;
import com.hinsight.product.model.dto.ProductSearchQuery;
import com.hinsight.product.model.dto.ProductSearchResult;
import com.hinsight.product.model.vo.CategoryGroup;
import com.hinsight.product.model.vo.PriceRange;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductDao productDao;
    private final CategoryService categoryService;
    private final ProductEsSearchService productEsSearchService;

    public List<Product> getAllProducts() {
        return productDao.getAllProducts();
    }

    public ProductDetailDto getProductDetailById(Long productId) {
        ProductDetailDto product = productDao.getProductDetailById(productId);
        if (product == null) {
            throw new ProductNotFoundException();
        }
        return product;
    }

    public ProductSearchResult searchProducts(ProductSearchCondition condition) {
        List<Long> categoryIds = resolveCategoryIds(condition);
        String keyword = condition.keyword();

        // 1. 검색어가 비면 Elastic Search 타지 않고 DB에서 검색
        if (keyword == null || keyword.isBlank()) {
            return ProductSearchResult.of(searchByDb(condition, categoryIds));
        }

        // 검색어가 있으면 ES(동의어·상품 키워드) 검색
        try {
            List<Product> results = productEsSearchService.searchProducts(
                    keyword, categoryIds, condition.minPrice(), condition.maxPrice());

            if (!results.isEmpty()) {
                return ProductSearchResult.of(results);
            }

            // 결과 0건이면 오타 교정(did-you-mean)을 시도해 교정어로 재검색
            String corrected = productEsSearchService.suggest(keyword);
            if (corrected != null && !corrected.equalsIgnoreCase(keyword)) {

                List<Product> alt = productEsSearchService.searchProducts(
                        corrected, categoryIds, condition.minPrice(), condition.maxPrice());

                if (!alt.isEmpty()) {
                    return ProductSearchResult.corrected(alt, keyword, corrected);
                }
            }
            return ProductSearchResult.of(List.of());
        } catch (Exception e) {
            // ES 장애 시 기존 LIKE 검색으로 폴백 (사이트가 죽지 않도록)
            log.warn("[ES] 검색 실패, LIKE 검색으로 폴백: {}", e.getMessage());
            return ProductSearchResult.of(searchByDb(condition, categoryIds));
        }
    }


    private List<Product> searchByDb(ProductSearchCondition condition, List<Long> categoryIds) {
        ProductSearchQuery query = new ProductSearchQuery(
                condition.keyword(), categoryIds, condition.minPrice(), condition.maxPrice());
        return productDao.search(query);
    }

    private List<Long> resolveCategoryIds(ProductSearchCondition condition) {
        if (condition.categoryId() != null) {
            return List.of(condition.categoryId());
        }

        if (condition.categoryGroup() != null && !condition.categoryGroup().isBlank()) {
            CategoryGroup group = CategoryGroup.valueOf(condition.categoryGroup());
            return categoryService.getCategoryIdsByGroup(group);
        }

        return null;
    }

    public PriceRange getPriceRange() {
        return productDao.getPriceRange();
    }
}

