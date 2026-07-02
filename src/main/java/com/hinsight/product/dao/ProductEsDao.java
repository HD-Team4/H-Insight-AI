package com.hinsight.product.dao;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SuggestMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.SuggestSort;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import co.elastic.clients.elasticsearch.core.search.TermSuggest;
import co.elastic.clients.elasticsearch.core.search.TermSuggestOption;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hinsight.product.es.ProductEsConstants;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ProductEsDao {

    private static final int MAX_RESULTS = 200;

    private final ElasticsearchClient esClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 조건에 맞는 상품 ID 목록을 ES에서 조회 (점수 기준 정렬)
     */
    public List<Long> searchIds(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice)
            throws IOException {

        Query query = buildQuery(keyword, categoryIds, minPrice, maxPrice);

        SearchResponse<Void> res = esClient.search(s -> s
                .index(ProductEsConstants.INDEX)
                .size(MAX_RESULTS)
                .source(src -> src.fetch(false)) // _source: false 처리
                .query(query), Void.class);

        return res.hits().hits().stream()
                .map(h -> Long.valueOf(h.id()))
                .toList();
    }

    /**
     * 오타 교정 기능
     */
    public String suggest(String keyword) throws IOException {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        SearchResponse<Void> res = esClient.search(s -> s
                .index(ProductEsConstants.INDEX)
                .size(0)
                .suggest(su -> su
                        .text(keyword)
                        .suggesters("s", fs -> fs
                                .term(t -> t
                                        .field("productName")
                                        .analyzer("standard")
                                        .suggestMode(SuggestMode.Missing)
                                        .minWordLength(2)
                                        .sort(SuggestSort.Frequency)
                                )
                        )
                ), Void.class);

        List<Suggestion<Void>> suggestions = res.suggest().get("s");
        if (suggestions == null || suggestions.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean anyCorrection = false;

        for (Suggestion<Void> suggestion : suggestions) {
            TermSuggest term = suggestion.term();
            String original = term.text();
            String chosen = original;

            List<TermSuggestOption> options = term.options();
            if (!options.isEmpty()) {
                String candidate = options.get(0).text();
                if (!candidate.equals(original)) {
                    chosen = candidate;
                    anyCorrection = true;
                }
            }

            if (!sb.isEmpty()) sb.append(" ");
            sb.append(chosen);
        }
        return anyCorrection ? sb.toString() : null;
    }


    private Query buildQuery(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice) {
        return Query.of(q -> q.bool(b -> {

            // [MUST] 키워드 매칭 조건
            if (keyword != null && !keyword.isBlank()) {
                b.must(m -> m.multiMatch(mm -> mm
                        .query(keyword)
                        .operator(Operator.And)
                        .fields("productName^2", "keywords", "description")
                ));
            } else {
                b.must(m -> m.matchAll(ma -> ma));
            }

            // [FILTER] 카테고리 및 가격 조건
            List<Query> filters = new ArrayList<>();

            // 1. 카테고리 ID 필터 (Terms Query)
            if (categoryIds != null && !categoryIds.isEmpty()) {
                List<FieldValue> fieldValues = categoryIds.stream()
                        .map(FieldValue::of)
                        .toList();

                filters.add(Query.of(f -> f.terms(t -> t
                        .field("categoryId")
                        .terms(v -> v.value(fieldValues))
                )));
            }

            // 2. 가격 범위 필터
            if (minPrice != null || maxPrice != null) {
                filters.add(Query.of(f -> f.range(r -> r
                        .untyped(u -> {
                            u.field("price");
                            if (minPrice != null) u.gte(JsonData.of(minPrice));
                            if (maxPrice != null) u.lte(JsonData.of(maxPrice));
                            return u;
                        })
                )));
            }

            if (!filters.isEmpty()) {
                b.filter(filters);
            }

            return b;
        }));
    }

}