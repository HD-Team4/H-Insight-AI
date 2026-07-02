package com.hinsight.product.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.synonyms.SynonymRule;
import com.hinsight.product.dao.SynonymDao;
import com.hinsight.product.es.ProductEsConstants;
import com.hinsight.product.model.vo.SynonymSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DB(synonym_set)의 동의어를 ES 동의어 세트로 반영한다.
 * updateable 필터라 세트를 갱신하면 검색 분석기가 자동 리로드된다(재색인 불필요).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SynonymSyncService {

    private final ElasticsearchClient esClient;
    private final SynonymDao synonymDao;

    public void syncToEs() throws IOException {
        List<SynonymRule> rules = new ArrayList<>();
        for (SynonymSet s : synonymDao.findAllActive()) {
            String normalized = normalize(s.getTerms());
            if (normalized.isEmpty()) continue;
            rules.add(SynonymRule.of(r -> r.id(String.valueOf(s.getSynonymId())).synonyms(normalized)));
        }
        if (rules.isEmpty()) {
            // ES 동의어 세트는 비어 있을 수 없으므로, 실제 텍스트와 매칭되지 않는 no-op 규칙을 넣는다.
            rules.add(SynonymRule.of(r -> r.id("_placeholder").synonyms("__noop__,__noop2__")));
        }

        List<SynonymRule> finalRules = rules;
        esClient.synonyms().putSynonym(p -> p
                .id(ProductEsConstants.SYNONYM_SET)
                .synonymsSet(finalRules));
        log.info("[ES] 동의어 세트 동기화 완료: {} rules", finalRules.size());
    }

    /** "denim, 진 ,청바지" -> "denim,진,청바지" (공백/빈 항목 정리) */
    private String normalize(String terms) {
        if (terms == null) return "";
        return Arrays.stream(terms.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining(","));
    }
}
