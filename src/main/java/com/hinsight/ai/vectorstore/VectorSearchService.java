package com.hinsight.ai.vectorstore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * pgvector 하이브리드 검색.
 * 정확 조건(성별·서브카테고리·가격)은 SQL WHERE 로 후보를 좁히고,
 * 의미(임베딩)는 코사인 거리(<=>)로 순위를 매긴다. HNSW 인덱스(vector_cosine_ops) 사용.
 */
@Service
public class VectorSearchService {

    private final JdbcTemplate jdbc;

    public VectorSearchService(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.jdbc = vectorJdbcTemplate;
    }

    public List<ProductMatch> search(String gender, String subcat,
                                     Integer minPrice, Integer maxPrice,
                                     float[] queryVector, int limit) {
        String vec = toVectorLiteral(queryVector);

        StringBuilder sql = new StringBuilder(
                "SELECT product_id, name, category, price, color, image_url, " +
                "       1 - (embedding <=> CAST(? AS vector)) AS score " +
                "FROM product_vectors WHERE TRUE");
        List<Object> args = new ArrayList<>();
        args.add(vec);   // SELECT 의 score 계산용

        if (notBlank(gender)) { sql.append(" AND gender = ?"); args.add(gender); }
        if (notBlank(subcat)) { sql.append(" AND subcat = ?"); args.add(subcat); }
        if (minPrice != null) { sql.append(" AND price >= ?"); args.add(minPrice); }
        if (maxPrice != null) { sql.append(" AND price <= ?"); args.add(maxPrice); }

        sql.append(" ORDER BY embedding <=> CAST(? AS vector) LIMIT ?");
        args.add(vec);   // ORDER BY 의 거리 계산용
        args.add(limit);

        return jdbc.query(sql.toString(), (rs, i) -> new ProductMatch(
                rs.getLong("product_id"),
                rs.getString("name"),
                rs.getString("category"),
                rs.getBigDecimal("price"),
                rs.getString("color"),
                rs.getString("image_url"),
                rs.getDouble("score")
        ), args.toArray());
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // float[] → pgvector 텍스트 리터럴 "[v1,v2,...]"
    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
