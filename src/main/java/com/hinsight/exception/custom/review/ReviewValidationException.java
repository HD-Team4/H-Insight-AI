package com.hinsight.exception.custom.review;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

/**
 * 리뷰 작성 입력 검증 실패.
 * 상품 정보·별점·내용 등 검증 항목별 ErrorCode 를 받아 하나의 타입으로 표현한다.
 */
public class ReviewValidationException extends BusinessException {
    public ReviewValidationException(ErrorCode errorCode) {
        super(errorCode);
    }
}
