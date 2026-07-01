package com.hinsight.product.controller;

import com.hinsight.product.model.dto.ProductSearchCondition;
import com.hinsight.product.model.dto.ProductSearchResult;
import com.hinsight.product.service.ProductService;
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


    @GetMapping("/{id}")
    public String getProduct(Model model, @PathVariable Long id) {
        model.addAttribute("product", productService.getProductById(id));
        return "customer/product/detail";
    }


}
