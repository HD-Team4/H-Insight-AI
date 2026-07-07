package com.hinsight.exception.custom.review;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

// 구매 이력이 없는 상품에 리뷰를 작성하려는 경우.
public class ReviewNotPurchasedException extends BusinessException {
    public ReviewNotPurchasedException() {
        super(ErrorCode.REVIEW_NOT_PURCHASED);
    }
}
