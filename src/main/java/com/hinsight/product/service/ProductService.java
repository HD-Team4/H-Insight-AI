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
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
        if (product == null) { //상품 관련 null exception 추가 , 추후 공통 예외처리
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
            // CategoryIds: 카테고리는 1개 선택, 상의 안에 니트(1), 셔츠(2)가 있다면 [1, 2] 이런식으로 보냄
            List<Product> results = esSearch(keyword, categoryIds, condition);
            if (!results.isEmpty()) {
                return ProductSearchResult.of(results);
            }

            // 결과 0건이면 오타 교정(did-you-mean)을 시도해 교정어로 재검색
            String corrected = productEsSearchService.suggest(keyword);
            if (corrected != null && !corrected.equalsIgnoreCase(keyword)) {
                List<Product> alt = esSearch(corrected, categoryIds, condition);
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

    /**
     * ES로 검색 후 점수 순서를 유지하며 DB에서 상품을 로드한다.
     */
    private List<Product> esSearch(String keyword, List<Long> categoryIds, ProductSearchCondition condition)
            throws IOException {
        List<Long> ids = productEsSearchService.searchIds(
                keyword, categoryIds, condition.minPrice(), condition.maxPrice());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> byId = productDao.findByIds(ids).stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p));
        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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

        return null; // 전체 상품 대상
    }

    public PriceRange getPriceRange() {
        return productDao.getPriceRange();
    }

}

