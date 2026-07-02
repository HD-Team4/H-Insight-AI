package com.hinsight.product.controller;

import com.hinsight.product.model.dto.ProductSearchCondition;
import com.hinsight.product.model.dto.ProductSearchResult;
import com.hinsight.product.service.ProductService;
import com.hinsight.product.support.ProductInfoFormatter;
import com.hinsight.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequiredArgsConstructor
@RequestMapping("/customer/products")
public class ProductController {

    private final ProductService productService;
    private final ProductInfoFormatter productInfoFormatter;
    private final ReviewService reviewService;

    @GetMapping
    public String productList(
            ProductSearchCondition condition,
            Model model
    ) {
        ProductSearchResult result = productService.searchProducts(condition);
        model.addAttribute("products", result.products());
        model.addAttribute("searchResult", result); // 오타 교정(did-you-mean) 안내용
        model.addAttribute("condition", condition);
        model.addAttribute("priceRange", productService.getPriceRange());
        return "customer/product/list";
    }

    /**
     * 무한스크롤| 요청한 페이지의 상품 카드 HTML 조각만 반환한다.
     */
    @GetMapping("/items")
    public String productItems(ProductSearchCondition condition, Model model) {
        ProductSearchResult result = productService.searchProducts(condition);
        model.addAttribute("products", result.products());
        return "customer/product/fragments/product-cards :: cards";
    }


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
