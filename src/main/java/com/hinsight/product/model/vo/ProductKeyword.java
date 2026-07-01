package com.hinsight.product.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

@Data
public class ProductKeyword extends BaseTimeVo {
    private Long keywordId;
    private Long productId;
    private String keyword;
}
