package com.hinsight.product.controller;

import com.hinsight.product.model.dto.ProductSearchCondition;
import com.hinsight.product.model.dto.ProductSearchResult;
import com.hinsight.product.service.ProductService;
import com.hinsight.product.support.ProductInfoFormatter;
import com.hinsight.review.service.ReviewService;
import com.hinsight.security.userdetails.CustomerUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "product-controller", description = "상품 뷰 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/products")
public class ProductController {

    private final ProductService productService;
    private final ProductInfoFormatter productInfoFormatter;
    private final ReviewService reviewService;

    @Operation(summary = "상품 목록 조회", description = "검색 조건으로 상품 목록 페이지를 렌더링한다. 오타 교정(did-you-mean) 안내 포함")
    @GetMapping
    public String productList(
            @AuthenticationPrincipal CustomerUserDetails user,   // 비로그인 시 null
            ProductSearchCondition condition,
            Model model
    ) {
        Long userId = (user == null) ? null : user.getUserId();
        ProductSearchResult result = productService.searchProductsWithLog(userId, condition);
        model.addAttribute("products", result.products());
        model.addAttribute("searchResult", result); // 오타 교정(did-you-mean) 안내용
        model.addAttribute("condition", condition);
        model.addAttribute("priceRange", productService.getPriceRange());
        return "customer/product/list";
    }

    /**
     * 무한스크롤| 요청한 페이지의 상품 카드 HTML 조각만 반환한다.
     */
    @Operation(summary = "상품 목록 조각(무한스크롤)", description = "요청한 페이지의 상품 카드 HTML 조각만 반환한다")
    @GetMapping("/items")
    public String productItems(ProductSearchCondition condition, Model model) {
        ProductSearchResult result = productService.searchProducts(condition);
        model.addAttribute("products", result.products());
        return "customer/product/fragments/product-cards :: cards";
    }


    @Operation(summary = "상품 상세 조회", description = "상품 상세 정보와 리뷰 목록을 조회해 상세 페이지를 렌더링한다")
    @GetMapping("/{id}")
    public String getProduct(Model model, @PathVariable Long id) {
        var product = productService.getProductDetailById(id);
        var reviews = reviewService.getReviewsByProductId(id);
        model.addAttribute("product", product);
        model.addAttribute("productInfoRows", productInfoFormatter.toRows(product.productInfo()));
        model.addAttribute("reviews", reviews);
        model.addAttribute("reviewCount", reviews.size());
        return "customer/product/detail";
    }
}
