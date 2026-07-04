package com.hinsight.review.model.dto;

import lombok.Data;

/**
 * 상품 상세 화면의 리뷰 작성 폼 요청. (별점 + 내용)
 * 감성(sentiment)은 배치(리뷰 Lambda)에서 채우므로 작성 시점엔 넣지 않는다.
 */
@Data
public class ReviewCreateRequest {

    private Long productId;
    private Integer rating;
    private String content;
}
