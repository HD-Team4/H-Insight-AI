// com.hinsight.product.model.vo.CategoryGroup
package com.hinsight.product.model.vo;

public enum CategoryGroup {
    TOP("상의"),
    BOTTOM("하의"),
    OUTER("아우터"),
    ACCESSORY("악세서리");

    private final String label;

    CategoryGroup(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static CategoryGroup classify(String categoryName) {
        if (categoryName == null) return null;

        if (categoryName.contains("아우터") || categoryName.contains("재킷")
                || categoryName.contains("베스트") || categoryName.contains("코트")) {
            return OUTER;
        }
        if (categoryName.contains("니트") || categoryName.contains("티셔츠")
                || categoryName.contains("셔츠") || categoryName.contains("블라우스")
                || categoryName.contains("원피스")) {
            return TOP;
        }
        if (categoryName.contains("팬츠") || categoryName.contains("스커트")
                || categoryName.contains("데님") || categoryName.contains("청바지")) {
            return BOTTOM;
        }
        if (categoryName.contains("패션잡화") || categoryName.contains("쥬얼리")
                || categoryName.contains("시계") || categoryName.contains("가방")
                || categoryName.contains("모자") || categoryName.contains("벨트")) {
            return ACCESSORY;
        }
        return null; // 남성/여성 최상위 카테고리는 그룹 없음
    }
}