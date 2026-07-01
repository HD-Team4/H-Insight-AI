package com.hinsight.cart.model.dto;

public record CartAddRequest (
        Long productId,
        Integer quantity
){
}
