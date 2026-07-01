package com.hinsight.product.controller;

import com.hinsight.product.model.dto.ProductSearchConditionDto;
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
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final ProductInfoFormatter productInfoFormatter;
    private final ReviewService reviewService;

    @GetMapping
    public String productList(
            ProductSearchConditionDto condition,
            Model model
    ) {
        model.addAttribute("products", productService.searchProducts(condition));
        model.addAttribute("condition", condition);
        model.addAttribute("priceRange", productService.getPriceRange());   // 추가
        return "customer/product/list";
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
