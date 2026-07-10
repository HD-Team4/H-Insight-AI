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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.product.es.ProductEsConstants;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ProductEsDao {

    private final ElasticsearchClient esClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /** ES 검색 결과: 상품 ID 목록 + (있다면) 오타 교정어 + 전체 매칭 건수 */
    public record EsSearchResult(List<Long> ids, String suggestion, long total) {}

    /**
     * 조건에 맞는 상품 ID 목록과 오타 교정어를 한 번의 ES 요청으로 조회한다.
     * query 와 suggest 를 함께 실어 왕복(round trip)을 최소화한다.
     * (정상어: 히트 반환 & 교정어 없음 / 오타어: 히트 0건 & 교정어 반환)
     * from/size 로 페이지네이션하며, 페이지 수 계산용 전체 건수는 total 로 반환한다.
     */
    public EsSearchResult search(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice,
                                 int offset, int limit) throws IOException {

        Query query = buildQuery(keyword, categoryIds, minPrice, maxPrice);
        boolean hasKeyword = keyword != null && !keyword.isBlank();

        SearchResponse<Void> res = esClient.search(s -> {
            s.index(ProductEsConstants.INDEX)
                    .from(offset)
                    .size(limit)
                    .trackTotalHits(t -> t.enabled(true))
                    .source(src -> src.fetch(false))
                    .query(query);

            // 키워드가 있을 때만 오타 교정 suggester 를 같은 요청에 함께 실음
            if (hasKeyword) {
                s.suggest(su -> su
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
                );
            }
            return s;
        }, Void.class);

        List<Long> ids = res.hits().hits().stream()
                .map(h -> Long.valueOf(h.id()))
                .toList();

        long total = (res.hits().total() != null) ? res.hits().total().value() : ids.size();

        return new EsSearchResult(ids, extractSuggestion(res), total);
    }

    /**
     * suggest 응답에서 교정어를 추출한다. 교정할 게 없으면 null.
     */
    private String extractSuggestion(SearchResponse<Void> res) {
        if (res.suggest() == null) {
            return null;
        }

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

            // 검색어 필터링
            if (keyword != null && !keyword.isBlank()) {
                b.must(m -> m.multiMatch(mm -> mm
                        .query(keyword)
                        .operator(Operator.And)
                        .fields("productName^2", "keywords", "description")
                ));
            } else {
                b.must(m -> m.matchAll(ma -> ma));
            }

            // 카테고리 | 가격 필터링
            List<Query> filters = new ArrayList<>();

            // 1. 카테고리 ID 필터
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
