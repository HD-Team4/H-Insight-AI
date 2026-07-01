package com.hinsight.product.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

@Data
public class Product extends BaseTimeVo {

    private Long productId;        // product_id
    private Long categoryId;       // category_id
    private Long bizUserId;        // biz_user_id
    private String productName;    // product_name
    private Integer price;         // price

    private String imageUrl;       // image_url
    private String description;    // description
    private String productInfo;    // product_info

}
