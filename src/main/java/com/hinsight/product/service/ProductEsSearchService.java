package com.hinsight.product.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hinsight.product.es.ProductEsConstants;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ES 기반 상품 검색. 매칭된 상품 ID를 점수 순으로 반환한다.
 * (실제 상품 데이터는 호출부에서 DB로 로드)
 */
@Service
@RequiredArgsConstructor
public class ProductEsSearchService {

    private static final int MAX_RESULTS = 200;

    private final ElasticsearchClient esClient;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public List<Long> searchIds(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice)
            throws IOException {

        // 1. 빌더 방식으로 조립된 Query 객체 가져오기
        Query query = buildQuery(keyword, categoryIds, minPrice, maxPrice);

        // 2. esClient의 기능을 온전히 활용하여 쿼리 수행
        SearchResponse<Void> res = esClient.search(s -> s
                .index(ProductEsConstants.INDEX)
                .size(MAX_RESULTS)
                .source(src -> src.fetch(false)) // _source: false 설정과 동일
                .query(query), Void.class);

        return res.hits().hits().stream()
                .map(h -> Long.valueOf(h.id()))
                .toList();
    }

    /**
     * 오타 교정(did-you-mean): productName 대상 term suggester로 교정어를 만든다.
     * 교정이 없으면 null 반환. 여러 토큰이면 토큰별 최선 후보를 합쳐 재구성한다.
     */
    public String suggest(String keyword) throws IOException {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        ObjectNode term = objectMapper.createObjectNode();
        term.put("field", "productName");
        term.put("analyzer", "standard");
        term.put("suggest_mode", "missing");
        term.put("min_word_length", 2);
        term.put("sort", "frequency");
        ObjectNode s = objectMapper.createObjectNode();
        s.put("text", keyword);
        s.set("term", term);
        ObjectNode suggest = objectMapper.createObjectNode();
        suggest.set("s", s);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", 0);
        body.set("suggest", suggest);

        Request req = new Request("POST", "/" + ProductEsConstants.INDEX + "/_search");
        req.setJsonEntity(body.toString());
        Response resp = restClient.performRequest(req);
        JsonNode entries = objectMapper.readTree(resp.getEntity().getContent())
                .path("suggest").path("s");
        if (!entries.isArray() || entries.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        boolean anyCorrection = false;
        for (JsonNode entry : entries) {
            String original = entry.path("text").asText();
            String chosen = original;
            JsonNode options = entry.path("options");
            if (options.isArray() && !options.isEmpty()) {
                String cand = options.get(0).path("text").asText(original);
                if (!cand.equals(original)) {
                    chosen = cand;
                    anyCorrection = true;
                }
            }
            if (sb.length() > 0) sb.append(" ");
            sb.append(chosen);
        }
        return anyCorrection ? sb.toString() : null;
    }

    /**
     * Java API Client를 사용하여 Type-Safe하게 쿼리를 빌드
     */
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

            // 2. 가격 범위 필터 (Range Query - ES 8.15+ 최신 스펙 반영)
            if (minPrice != null || maxPrice != null) {
                filters.add(Query.of(f -> f.range(r -> r
                        .untyped(u -> {
                            u.field("price");
                            if (minPrice != null) {
                                u.gte(JsonData.of(minPrice));
                            }
                            if (maxPrice != null) {
                                u.lte(JsonData.of(maxPrice));
                            }
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
