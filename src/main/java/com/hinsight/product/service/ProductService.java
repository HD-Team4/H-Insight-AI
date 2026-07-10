package com.hinsight.product.service;

import com.hinsight.common.event.ActivityEvent;
import com.hinsight.common.event.ActivityEventPublisher;
import com.hinsight.exception.custom.product.ProductNotFoundException;
import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.dto.PageInfo;
import com.hinsight.product.model.dto.ProductDetailDto;
import com.hinsight.product.model.dto.ProductSearchCondition;
import com.hinsight.product.model.dto.ProductSearchQuery;
import com.hinsight.product.model.dto.ProductSearchResult;
import com.hinsight.product.model.dto.SearchLogPayload;
import com.hinsight.product.model.dto.ClickLogPayload;
import com.hinsight.product.model.vo.CategoryGroup;
import com.hinsight.product.model.vo.PriceRange;
import com.hinsight.product.model.vo.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final String TOPIC_SEARCH = "activity.search";
    private static final String TOPIC_CLICK = "activity.click";

    @Value("${product.list.page-size:20}")
    private int pageSize;

    private final ProductDao productDao;
    private final CategoryService categoryService;
    private final ProductEsSearchService productEsSearchService;
    private final ActivityEventPublisher activityEventPublisher; // 검색/클릭 로그 → 데이터레이크
    private final RecentViewService recentViewService;           // 최근 본 상품 → Redis

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

    /**
     * 상품 상세 조회 후 진입 기록을 남긴다 — 클릭 로그(activity.click) 발행 + 최근 본 상품(Redis) 저장.
     * 상세 페이지 진입(사용자가 상품을 클릭해 들어옴)에서만 호출.
     */
    public ProductDetailDto getProductDetailByIdWithLog(Long userId, Long productId) {
        ProductDetailDto product = getProductDetailById(productId);
        publishClickLog(userId, product);
        recentViewService.add(userId, productId);
        return product;
    }

    // 마이페이지 최근 본 상품 | Redis의 최근 조회순
    public List<Product> getRecentViewedProducts(Long userId) {
        List<Long> productIds = recentViewService.getRecentProductIds(userId);
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productDao.findByIds(productIds);
    }

    /**
     * 검색 후 결과를 검색 로그(activity.search)로 발행한다.
     * 실제 검색 진입(목록 페이지)에서만 호출 — 무한스크롤 조각 요청은 같은 검색의 페이지네이션이라 발행 X
     */
    public ProductSearchResult searchProductsWithLog(Long userId, ProductSearchCondition condition) {
        ProductSearchResult result = searchProducts(condition);
        publishSearchLog(userId, condition, result);
        return result;
    }

    public ProductSearchResult searchProducts(ProductSearchCondition condition) {
        List<Long> categoryIds = resolveCategoryIds(condition);
        String keyword = condition.keyword();

        int page = (condition.page() == null || condition.page() < 1) ? 1 : condition.page();
        int offset = (page - 1) * pageSize;

        // 1. 검색어가 비면 Elastic Search 타지 않고 DB에서 검색
        if (keyword == null || keyword.isBlank()) {
            return searchByDb(condition, categoryIds, page, offset);
        }

        // 검색어가 있으면 ES(동의어·상품 키워드) 검색 — 오타 교정 재검색까지 ES 서비스가 담당
        try {
            ProductEsSearchService.SearchOutcome outcome = productEsSearchService.search(
                    keyword, categoryIds, condition.minPrice(), condition.maxPrice(), offset, pageSize);

            PageInfo pageInfo = PageInfo.of(page, pageSize, outcome.total());
            return outcome.corrected()
                    ? ProductSearchResult.corrected(outcome.products(), outcome.originalKeyword(),
                            outcome.correctedKeyword(), pageInfo)
                    : ProductSearchResult.of(outcome.products(), pageInfo);
        } catch (Exception e) {
            // ES 장애 시 기존 LIKE 검색으로 폴백 (사이트가 죽지 않도록)
            log.warn("[ES] 검색 실패, LIKE 검색으로 폴백: {}", e.getMessage());
            return searchByDb(condition, categoryIds, page, offset);
        }
    }


    // 검색어/결과를 봉투에 담아 데이터레이크로 발행. 분석용이라 실패해도 검색 흐름은 막지 않는다.
    private void publishSearchLog(Long userId, ProductSearchCondition condition, ProductSearchResult result) {
        // 비회원 로깅 제외
        if (userId == null) {
            return;
        }
        // 검색어 기반 로그만 기록
        if (condition.keyword() == null || condition.keyword().isBlank()) {
            return;
        }

        // 오타인 경우 교정어, 아니면 입력키워드 기록 (추천용이기 때문에)
        String keyword = result.corrected() ? result.correctedKeyword() : condition.keyword();

        SearchLogPayload payload = SearchLogPayload.builder()
                .keyword(keyword)
                .page(result.page().page())
                .resultCount(result.page().totalElements())
                .build();

        activityEventPublisher.publish(TOPIC_SEARCH, ActivityEvent.of("search", userId, payload));
    }

    private void publishClickLog(Long userId, ProductDetailDto product) {
        // 비회원 로깅 제외
        if (userId == null) {
            return;
        }

        ClickLogPayload payload = ClickLogPayload.builder()
                .productId(product.productId())
                .productName(product.productName())
                .brandName(product.brandName())
                .price(product.price())
                .build();

        activityEventPublisher.publish(TOPIC_CLICK, ActivityEvent.of("click", userId, payload));
    }

    private ProductSearchResult searchByDb(ProductSearchCondition condition, List<Long> categoryIds, int page, int offset) {
        ProductSearchQuery query = new ProductSearchQuery(
                condition.keyword(), categoryIds, condition.minPrice(), condition.maxPrice(), offset, pageSize);
        List<Product> products = productDao.search(query);
        long total = productDao.countSearch(query);
        return ProductSearchResult.of(products, PageInfo.of(page, pageSize, total));
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

    // 판매 개선 승인 반영 — biz_id 일치하는 행이 없으면 false(다른 기업 상품 보호)
    public boolean updateProductNameForBiz(Long productId, Long bizId, String productName) {
        return productDao.updateProductName(productId, bizId, productName) > 0;
    }

    public boolean updateDescriptionForBiz(Long productId, Long bizId, String description) {
        return productDao.updateDescription(productId, bizId, description) > 0;
    }

    public boolean promoteProductForBiz(Long productId, Long bizId) {
        return productDao.promoteProduct(productId, bizId) > 0;
    }

    public Set<Long> getOwnedProductIds(Long bizId) {
        return new HashSet<>(productDao.findProductIdsByBiz(bizId));
    }

    public List<Product> getPromotedProducts(int limit) {
        return productDao.findPromotedProducts(limit);
    }
}
