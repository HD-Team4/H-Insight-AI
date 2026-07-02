package com.hinsight.product.dao;

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