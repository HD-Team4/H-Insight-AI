package com.hinsight.product.service;

import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.dto.ProductSearchConditionDto;
import com.hinsight.product.model.dto.ProductSearchQuery;
import com.hinsight.product.model.vo.CategoryGroup;
import com.hinsight.product.model.vo.PriceRange;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    public Product getProductById(Long productId) {
        return productDao.getProductById(productId);
    }

    public List<Product> searchProducts(ProductSearchConditionDto condition) {
        List<Long> categoryIds = resolveCategoryIds(condition);

        // 검색어가 없으면(브라우징/카테고리·가격 필터만) DB로 직접 조회한다.
        // 전체 목록이 ES 인덱스 상태(빈 인덱스·재색인 중·장애)에 좌우되지 않도록 하기 위함.
        if (condition.keyword() == null || condition.keyword().isBlank()) {
            return searchByDb(condition, categoryIds);
        }

        // 검색어가 있으면 ES(동의어·상품 키워드) 검색을 사용한다.
        try {
            List<Long> ids = productEsSearchService.searchIds(
                    condition.keyword(), categoryIds, condition.minPrice(), condition.maxPrice());
            if (ids.isEmpty()) {
                return List.of();
            }
            // ES 점수 순서를 유지하며 DB에서 상품을 로드
            Map<Long, Product> byId = productDao.findByIds(ids).stream()
                    .collect(Collectors.toMap(Product::getProductId, p -> p));
            return ids.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // ES 장애 시 기존 LIKE 검색으로 폴백 (사이트가 죽지 않도록)
            log.warn("[ES] 검색 실패, LIKE 검색으로 폴백: {}", e.getMessage());
            return searchByDb(condition, categoryIds);
        }
    }

    private List<Product> searchByDb(ProductSearchConditionDto condition, List<Long> categoryIds) {
        ProductSearchQuery query = new ProductSearchQuery(
                condition.keyword(), categoryIds, condition.minPrice(), condition.maxPrice());
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

