package com.hinsight.ai.rag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * review_vectors 적재 운영용 엔드포인트.
 *  - POST /api/rag/sync-written-at : 기존 벡터 행의 written_at 을 MySQL created_at 으로 채움 (재임베딩 X, 최초 1회 필수)
 *  - POST /api/rag/embed-missing   : 벡터에 아직 없는 신규 리뷰만 임베딩해 삽입
 *  - POST /api/rag/official-spec   : 공식 스펙(리뉴얼 안내) 1건 적재
 */
@Tag(name = "rag-admin-controller", description = "RAG 적재 운영 컨트롤러")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/rag")
public class RagAdminController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReviewIngestService reviewIngestService;

    @Operation(summary = "written_at 동기화", description = "기존 벡터 행의 written_at 을 MySQL created_at 으로 채움(재임베딩 없음). 최초 1회 필수")
    @PostMapping("/sync-written-at")
    public Map<String, Object> syncWrittenAt(@RequestParam(defaultValue = "500") int batchSize) {
        int count = reviewIngestService.syncWrittenAt(batchSize);
        return Map.of("updated", count);
    }

    @Operation(summary = "신규 리뷰 임베딩", description = "벡터에 아직 없는 리뷰만 임베딩해 삽입")
    @PostMapping("/embed-missing")
    public Map<String, Object> embedMissing(@RequestParam(defaultValue = "200") int batchSize) {
        int count = reviewIngestService.embedMissingReviews(batchSize);
        return Map.of("inserted", count);
    }

    @Operation(summary = "공식 스펙 적재", description = "제조사 공식 스펙/리뉴얼 안내를 우선순위 컨텍스트로 적재")
    @PostMapping("/official-spec")
    public Map<String, Object> ingestOfficialSpec(@RequestBody OfficialSpecRequest req) {
        OffsetDateTime writtenAt = (req.writtenAt() != null && !req.writtenAt().isBlank())
                ? LocalDate.parse(req.writtenAt().trim()).atStartOfDay(KST).toOffsetDateTime()
                : null;
        reviewIngestService.ingestOfficialSpec(req.productId(), req.content(), writtenAt, req.renewalVersion());
        return Map.of("status", "ok", "productId", req.productId(), "renewalVersion", req.renewalVersion());
    }

    /**
     * @param productId      대상 상품
     * @param content        공식 스펙 본문
     * @param writtenAt      게시일 "yyyy-MM-dd" (null 이면 now)
     * @param renewalVersion 리뉴얼 버전 태그 (예: "2026-03-RENEWAL")
     */
    public record OfficialSpecRequest(
            Long productId,
            String content,
            String writtenAt,
            String renewalVersion
    ) {
    }
}
