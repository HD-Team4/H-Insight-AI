package com.hinsight.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    // 공통
    INVALID_INPUT("INVALID_INPUT", "입력값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    LOGIN_REQUIRED("LOGIN_REQUIRED", "로그인이 필요합니다.", HttpStatus.UNAUTHORIZED),

    USER_NOT_FOUND("USER_NOT_FOUND", "회원을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    DUPLICATE_LOGIN_ID("DUPLICATE_LOGIN_ID", "이미 사용 중인 아이디입니다.", HttpStatus.CONFLICT),
    INVALID_PASSWORD("INVALID_PASSWORD", "비밀번호가 일치하지 않습니다.", HttpStatus.BAD_REQUEST),
    PRODUCT_NOT_FOUND("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    CART_ITEM_NOT_FOUND("CART_ITEM_NOT_FOUND", "장바구니 항목을 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    OUT_OF_STOCK("OUT_OF_STOCK", "재고가 부족합니다.", HttpStatus.CONFLICT),
    PAYMENT_FAILED("PAYMENT_FAILED", "결제에 실패했습니다.", HttpStatus.BAD_REQUEST),
    REVIEW_PERMISSION_DENIED("REVIEW_PERMISSION_DENIED", "리뷰 권한이 없습니다.", HttpStatus.FORBIDDEN),

    // 리뷰 작성 검증
    REVIEW_INVALID_PRODUCT("REVIEW_INVALID_PRODUCT", "상품 정보가 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    REVIEW_NOT_PURCHASED("REVIEW_NOT_PURCHASED", "구매한 상품만 리뷰를 작성할 수 있습니다.", HttpStatus.FORBIDDEN),
    REVIEW_INVALID_RATING("REVIEW_INVALID_RATING", "별점은 1~5점 사이로 선택해 주세요.", HttpStatus.BAD_REQUEST),
    REVIEW_CONTENT_REQUIRED("REVIEW_CONTENT_REQUIRED", "리뷰 내용을 입력해 주세요.", HttpStatus.BAD_REQUEST),
    REVIEW_CONTENT_TOO_LONG("REVIEW_CONTENT_TOO_LONG", "리뷰는 최대 1000자까지 작성할 수 있습니다.", HttpStatus.BAD_REQUEST),

    UNAUTHORIZED_DASHBOARD_ACCESS("UNAUTHORIZED_DASHBOARD_ACCESS", "대시보드 접근 권한이 없습니다.", HttpStatus.FORBIDDEN),
    PRODUCT_ACCESS_DENIED("PRODUCT_ACCESS_DENIED", "해당 상품에 대한 권한이 없습니다.", HttpStatus.FORBIDDEN),

    // 검색
    SEARCH_FAILED("SEARCH_FAILED", "검색 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE),

    // 노션 연동
    NOTION_NOT_CONFIGURED("NOTION_NOT_CONFIGURED", "노션 연동이 설정되지 않았습니다.", HttpStatus.INTERNAL_SERVER_ERROR),
    NOTION_PAGE_NOT_LINKED("NOTION_PAGE_NOT_LINKED", "연결된 노션 페이지가 없습니다.", HttpStatus.BAD_REQUEST),
    NOTION_IMAGE_REQUIRED("NOTION_IMAGE_REQUIRED", "전송할 이미지가 비어 있습니다.", HttpStatus.BAD_REQUEST),
    NOTION_API_ERROR("NOTION_API_ERROR", "노션 연동 처리 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY),

    // 데이터 마트 로드
    MART_LOAD_FAILED("MART_LOAD_FAILED", "데이터를 불러오지 못했습니다.", HttpStatus.INTERNAL_SERVER_ERROR),

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
