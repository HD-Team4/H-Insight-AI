package com.hinsight.product.controller;

import com.hinsight.product.service.ProductIndexService;
import com.hinsight.product.service.SynonymSyncService;
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
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/es")
public class EsAdminController {

    private final ProductIndexService productIndexService;
    private final SynonymSyncService synonymSyncService;

    @PostMapping("/init")
    public Map<String, Object> init() throws Exception {
        boolean created = productIndexService.createIndexIfAbsent();
        return Map.of("indexCreated", created);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() throws Exception {
        int count = productIndexService.reindexAll();
        return Map.of("indexed", count);
    }

    @PostMapping("/sync-synonyms")
    public Map<String, Object> syncSynonyms() throws Exception {
        synonymSyncService.syncToEs();
        return Map.of("status", "ok");
    }
}
