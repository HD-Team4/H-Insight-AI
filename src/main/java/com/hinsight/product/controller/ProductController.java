package com.hinsight.product.controller;

import com.hinsight.product.model.dto.ProductSearchConditionDto;
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
            ProductSearchConditionDto condition,
            Model model
    ) {
        model.addAttribute("products", productService.searchProducts(condition));
        model.addAttribute("condition", condition);
        return "customer/product/list";
    }



    @GetMapping("/{id}")
    public String getProduct(Model model, @PathVariable Long id) {
        model.addAttribute("product", productService.getProductById(id));
        return "customer/product/detail";
    }


}
