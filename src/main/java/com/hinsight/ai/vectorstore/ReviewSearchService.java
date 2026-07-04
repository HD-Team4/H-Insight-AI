package com.hinsight.ai.vectorstore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * review_vectors 2단계 하이브리드 검색 (라이브 방송 리뷰 기반 Q&A용).
 *
 * <p>가중합만으로는 공식 스펙이 유사도가 낮을 때 top-K 밖으로 밀릴 수 있으므로,
 * "공식 스펙"과 "유저 리뷰"를 서로 다른 트랙으로 뽑아 공식 스펙의 컨텍스트 진입을 보장한다.</p>
 * <ul>
 *   <li>{@link #searchOfficial} : is_official=TRUE, 날짜 컷오프 무시(항상 후보), 코사인 순.</li>
 *   <li>{@link #searchReviews}  : written_at &gt;= cutoff 로 구형 리뷰 차단 후,
 *       wSim*sim + wRec*recency 로 재랭킹.</li>
 * </ul>
 * HNSW(vector_cosine_ops) + (product_id, written_at) 인덱스 사용. {@link VectorSearchService} 와 동일한
 * 벡터 전용 JdbcTemplate·pgvector 리터럴 관례를 따른다.
 */
@Service
public class ReviewSearchService {

    private final JdbcTemplate jdbc;

    public ReviewSearchService(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.jdbc = vectorJdbcTemplate;
    }

    /** 공식 스펙 트랙: 날짜 무관하게 항상 후보. 제품의 '현재 사실'. */
    public List<ReviewMatch> searchOfficial(long productId, float[] queryVector, int limit) {
        String vec = toVectorLiteral(queryVector);
        String sql =
                "SELECT id, product_id, content, is_official, written_at, " +
                "       1 - (embedding <=> CAST(? AS vector)) AS sim " +
                "FROM review_vectors " +
                "WHERE product_id = ? AND is_official = TRUE " +
                "ORDER BY embedding <=> CAST(? AS vector) " +
                "LIMIT ?";
        return jdbc.query(sql, officialRowMapper(), vec, productId, vec, limit);
    }

    /**
     * 유저 리뷰 트랙: 컷오프 이후 리뷰만 대상으로 시간 가중치 재랭킹.
     *
     * @param cutoff       리뉴얼 기준일. 이전 리뷰는 검색 대상에서 완전 제외.
     * @param halfLifeDays recency 반감기(일). 예: 90 이면 90일 전 리뷰의 recency=0.5.
     * @param wSim         의미 유사도 가중 (예: 0.6)
     * @param wRec         최신성 가중 (예: 0.4)
     */
    public List<ReviewMatch> searchReviews(long productId, float[] queryVector, OffsetDateTime cutoff,
                                           int limit, double halfLifeDays, double wSim, double wRec) {
        String vec = toVectorLiteral(queryVector);
        String sql =
                "WITH scored AS ( " +
                "  SELECT id, product_id, content, is_official, written_at, " +
                "         1 - (embedding <=> CAST(? AS vector)) AS sim, " +
                "         POWER(0.5, EXTRACT(EPOCH FROM (now() - written_at)) / (86400.0 * ?)) AS recency " +
                "  FROM review_vectors " +
                "  WHERE product_id = ? AND is_official = FALSE AND written_at >= ? " +
                ") " +
                "SELECT id, product_id, content, is_official, written_at, sim, recency, " +
                "       (? * sim + ? * recency) AS final_score " +
                "FROM scored " +
                "ORDER BY final_score DESC " +
                "LIMIT ?";
        return jdbc.query(sql, reviewRowMapper(),
                vec, halfLifeDays, productId, cutoff, wSim, wRec, limit);
    }

    private RowMapper<ReviewMatch> officialRowMapper() {
        return (rs, i) -> new ReviewMatch(
                rs.getLong("id"),
                (Long) rs.getObject("product_id"),
                rs.getString("content"),
                rs.getBoolean("is_official"),
                rs.getObject("written_at", OffsetDateTime.class),
                rs.getDouble("sim"),
                1.0,   // 공식 스펙은 recency 개념 미적용
                0.0);
    }

    private RowMapper<ReviewMatch> reviewRowMapper() {
        return (rs, i) -> new ReviewMatch(
                rs.getLong("id"),
                (Long) rs.getObject("product_id"),
                rs.getString("content"),
                rs.getBoolean("is_official"),
                rs.getObject("written_at", OffsetDateTime.class),
                rs.getDouble("sim"),
                rs.getDouble("recency"),
                rs.getDouble("final_score"));
    }

    // float[] → pgvector 텍스트 리터럴 "[v1,v2,...]" (VectorSearchService 와 동일 관례)
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
