package com.hinsight.cart.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

@Data
public class Cart extends BaseTimeVo {
    private Long cartId;
    private Long userId;
    private Long productId;
    private Integer quantity;
}
