package com.hinsight.product.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

@Data
public class Category extends BaseTimeVo {
    private Long categoryId;
    private Long parentCategoryId;
    private String categoryName;
}
