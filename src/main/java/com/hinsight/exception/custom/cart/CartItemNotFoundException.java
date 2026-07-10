package com.hinsight.exception.custom.cart;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class CartItemNotFoundException extends BusinessException {
    public CartItemNotFoundException() {
        super(ErrorCode.CART_ITEM_NOT_FOUND);
    }
}
