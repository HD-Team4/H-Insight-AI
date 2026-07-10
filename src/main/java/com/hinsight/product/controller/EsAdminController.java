package com.hinsight.product.controller;

import com.hinsight.product.service.ProductIndexService;
import com.hinsight.product.service.SynonymSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ES 색인/동의어 운영용 엔드포인트.
 *  - POST /api/es/init          : 인덱스 없으면 생성 (+ 동의어 세트 생성)
 *  - POST /api/es/reindex       : 전체 상품 재색인
 *  - POST /api/es/sync-synonyms : DB 동의어 -> ES 반영 (검색 분석기 자동 리로드)
 */
@Tag(name = "es-admin-controller", description = "ES 색인/동의어 운영 컨트롤러")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/es")
public class EsAdminController {

    private final ProductIndexService productIndexService;
    private final SynonymSyncService synonymSyncService;

    @Operation(summary = "인덱스 초기화", description = "인덱스가 없으면 생성한다(+ 동의어 세트 생성)")
    @PostMapping("/init")
    public Map<String, Object> init() throws Exception {
        boolean created = productIndexService.createIndexIfAbsent();
        return Map.of("indexCreated", created);
    }

    @Operation(summary = "전체 재색인", description = "DB의 전체 상품을 ES로 다시 색인한다")
    @PostMapping("/reindex")
    public Map<String, Object> reindex() throws Exception {
        int count = productIndexService.reindexAll();
        return Map.of("indexed", count);
    }

    @Operation(summary = "동의어 동기화", description = "DB 동의어를 ES에 반영한다(검색 분석기 자동 리로드)")
    @PostMapping("/sync-synonyms")
    public Map<String, Object> syncSynonyms() throws Exception {
        synonymSyncService.syncToEs();
        return Map.of("status", "ok");
    }
}
