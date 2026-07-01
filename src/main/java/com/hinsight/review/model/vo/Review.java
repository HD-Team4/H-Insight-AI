package com.hinsight.review.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class Review extends BaseTimeVo {

    private Long reviewId;
    private Long productId;
    private Long userId;
    private String content;
    private Integer rating;
    private String sentiment;

    public String getRatingStars() {
        int starCount = rating == null ? 0 : Math.max(0, Math.min(rating, 5));
        return "★".repeat(starCount) + "☆".repeat(5 - starCount);
    }
}
