package com.hinsight.product.model.dto;

/**
 * 목록 페이지네이션 정보.
 * page 는 1-based 이며, totalPages 는 totalElements/size 로 계산한다.
 */
public record PageInfo(int page, int size, long totalElements, int totalPages) {

    public static PageInfo of(int page, int size, long totalElements) {
        int totalPages = (size <= 0) ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageInfo(page, size, totalElements, totalPages);
    }

    public boolean hasPrev() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }
}
