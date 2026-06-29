package com.hinsight.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    USER_NOT_FOUND("USER_NOT_FOUND", "회원을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    DUPLICATE_LOGIN_ID("DUPLICATE_LOGIN_ID", "이미 사용 중인 아이디입니다.", HttpStatus.CONFLICT),
    INVALID_PASSWORD("INVALID_PASSWORD", "비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CART_ITEM_NOT_FOUND("CART_ITEM_NOT_FOUND", "장바구니 항목을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    OUT_OF_STOCK("OUT_OF_STOCK", "재고가 부족합니다.", HttpStatus.CONFLICT),
    PAYMENT_FAILED("PAYMENT_FAILED", "결제에 실패했습니다.", HttpStatus.BAD_REQUEST),
    REVIEW_PERMISSION_DENIED("REVIEW_PERMISSION_DENIED", "리뷰 권한이 없습니다.", HttpStatus.FORBIDDEN),
    UNAUTHORIZED_DASHBOARD_ACCESS("UNAUTHORIZED_DASHBOARD_ACCESS", "대시보드 접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    EMBEDDING_FAILED("EMBEDDING_FAILED", "임베딩 처리에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    VECTOR_SEARCH_FAILED("VECTOR_SEARCH_FAILED", "벡터 검색에 실패했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR("INTERNAL_ERROR", "서버 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
