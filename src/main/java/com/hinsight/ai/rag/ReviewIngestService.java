package com.hinsight.ai.rag;

import com.hinsight.ai.embedding.EmbeddingService;
import com.hinsight.review.dao.ReviewDao;
import com.hinsight.review.model.dto.ReviewDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * review_vectors 적재 파이프라인.
 *
 * <p>기존 3,000행은 이미 content·embedding·product_id 가 채워져 있고 written_at 만 비어 있으므로,
 * 재임베딩 없이 날짜만 채우는 {@link #syncWrittenAt} 가 실질적인 백필이다.
 * 벡터에 아직 없는 신규 리뷰만 {@link #embedMissingReviews} 로 임베딩해 넣는다.
 * 공식 스펙은 {@link #ingestOfficialSpec} 로 is_official=TRUE 적재.</p>
 *
 * <p>MySQL(ReviewDao) 에서 읽고 Postgres(vectorJdbcTemplate) 로 쓰는 크로스 DB 작업이다.</p>
 */
@Service
public class ReviewIngestService {

    private static final Logger log = LoggerFactory.getLogger(ReviewIngestService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReviewDao reviewDao;
    private final EmbeddingService embeddingService;
    private final JdbcTemplate vectorJdbc;

    public ReviewIngestService(ReviewDao reviewDao,
                               EmbeddingService embeddingService,
                               @Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.reviewDao = reviewDao;
        this.embeddingService = embeddingService;
        this.vectorJdbc = vectorJdbcTemplate;
    }

    private static final String UPDATE_WRITTEN_AT =
            "UPDATE review_vectors SET written_at = ? WHERE review_id = ?";

    private static final String INSERT_REVIEW =
            "INSERT INTO review_vectors " +
            "  (review_id, product_id, content, embedding, source_type, is_official, written_at) " +
            "VALUES (?, ?, ?, CAST(? AS vector), 'REVIEW', FALSE, ?) " +
            "ON CONFLICT (review_id) DO UPDATE SET " +   // PK 충돌 시(경합) 안전하게 갱신
            "  content = EXCLUDED.content, embedding = EXCLUDED.embedding, " +
            "  product_id = EXCLUDED.product_id, written_at = EXCLUDED.written_at";

    private static final String UPSERT_OFFICIAL =
            "INSERT INTO review_vectors " +
            "  (product_id, content, embedding, source_type, is_official, written_at, renewal_version) " +
            "VALUES (?, ?, CAST(? AS vector), 'OFFICIAL', TRUE, ?, ?) " +
            "ON CONFLICT (product_id, renewal_version) WHERE is_official = TRUE DO UPDATE SET " +
            "  content = EXCLUDED.content, embedding = EXCLUDED.embedding, written_at = EXCLUDED.written_at";

    /**
     * 기존 벡터 행의 written_at 을 MySQL reviews.created_at 으로 채운다. (재임베딩 없음)
     * 시간 가중치·컷오프 필터가 동작하려면 반드시 1회 실행해야 한다. 여러 번 돌려도 안전.
     *
     * @param batchSize 한 번에 읽을 리뷰 수 (예: 500)
     * @return written_at 이 갱신된 행 수 (= 벡터에 존재하는 리뷰 수)
     */
    public int syncWrittenAt(int batchSize) {
        long lastId = 0;
        int updated = 0;
        while (true) {
            List<ReviewDto> batch = reviewDao.findBatchForEmbedding(lastId, batchSize);
            if (batch.isEmpty()) break;

            List<Object[]> args = new ArrayList<>(batch.size());
            for (ReviewDto r : batch) {
                args.add(new Object[]{ toKst(r.getCreatedAt()), r.getReviewId() });
                lastId = r.getReviewId();
            }
            for (int cnt : vectorJdbc.batchUpdate(UPDATE_WRITTEN_AT, args)) {
                updated += cnt;   // 벡터에 없는 신규 리뷰는 0
            }
            log.info("[RAG적재] written_at 동기화 진행 lastId={}, 누적갱신={}", lastId, updated);
        }
        log.info("[RAG적재] written_at 동기화 완료: {}행", updated);
        return updated;
    }

    /**
     * 벡터에 아직 없는 리뷰만 임베딩해 삽입한다. (신규 리뷰 증분 적재)
     *
     * @param batchSize 한 번에 읽을 리뷰 수 (예: 200)
     * @return 새로 임베딩·삽입한 건수
     */
    public int embedMissingReviews(int batchSize) {
        Set<Long> existing = new HashSet<>(
                vectorJdbc.queryForList("SELECT review_id FROM review_vectors WHERE review_id IS NOT NULL", Long.class));

        long lastId = 0;
        int inserted = 0;
        while (true) {
            List<ReviewDto> batch = reviewDao.findBatchForEmbedding(lastId, batchSize);
            if (batch.isEmpty()) break;

            for (ReviewDto r : batch) {
                lastId = r.getReviewId();
                if (existing.contains(r.getReviewId())) continue;   // 이미 임베딩된 리뷰는 skip
                try {
                    float[] vec = embeddingService.embed(r.getContent());
                    vectorJdbc.update(INSERT_REVIEW,
                            r.getReviewId(), r.getProductId(), r.getContent(),
                            toVectorLiteral(vec), toKst(r.getCreatedAt()));
                    inserted++;
                } catch (Exception e) {
                    log.warn("[RAG적재] 리뷰 임베딩 실패 reviewId={} : {}", r.getReviewId(), e.getMessage());
                }
            }
            log.info("[RAG적재] 신규 임베딩 진행 lastId={}, 누적={}", lastId, inserted);
        }
        log.info("[RAG적재] 신규 리뷰 임베딩 완료: {}건", inserted);
        return inserted;
    }

    /**
     * 공식 스펙(리뉴얼 안내) 1건 적재. (product_id, renewalVersion) 당 1건으로 업서트.
     *
     * @param productId      대상 상품
     * @param content        공식 스펙/리뉴얼 안내 본문
     * @param writtenAt      스펙 게시일 (null 이면 now())
     * @param renewalVersion 리뉴얼 버전 태그 (예: "2026-03-RENEWAL")
     */
    public void ingestOfficialSpec(long productId, String content,
                                   OffsetDateTime writtenAt, String renewalVersion) {
        float[] vec = embeddingService.embed(content);
        vectorJdbc.update(UPSERT_OFFICIAL,
                productId,
                content,
                toVectorLiteral(vec),
                writtenAt != null ? writtenAt : OffsetDateTime.now(KST),
                renewalVersion);
        log.info("[RAG적재] 공식 스펙 적재 productId={}, version={}", productId, renewalVersion);
    }

    private OffsetDateTime toKst(LocalDateTime dt) {
        return (dt != null ? dt : LocalDateTime.now()).atZone(KST).toOffsetDateTime();
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
