package com.hinsight.product.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hinsight.product.es.ProductEsConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

/**
 * ES 기반 상품 검색. 매칭된 상품 ID를 점수 순으로 반환한다.
 * (실제 상품 데이터는 호출부에서 DB로 로드)
 * 쿼리 본문은 JSON으로 조립해 클라이언트 타입 API의 버전 차이를 피한다.
 */
@Service
@RequiredArgsConstructor
public class ProductEsSearchService {

    private static final int MAX_RESULTS = 200;

    private final ElasticsearchClient esClient;
    private final ObjectMapper objectMapper;

    public List<Long> searchIds(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice)
            throws IOException {
        ObjectNode body = buildQuery(keyword, categoryIds, minPrice, maxPrice);

        SearchResponse<Void> res = esClient.search(s -> s
                .index(ProductEsConstants.INDEX)
                .withJson(new StringReader(body.toString())), Void.class);

        return res.hits().hits().stream()
                .map(h -> Long.valueOf(h.id()))
                .toList();
    }

    private ObjectNode buildQuery(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", MAX_RESULTS);
        root.put("_source", false);

        ObjectNode bool = objectMapper.createObjectNode();

        // must: 키워드 매칭 (없으면 전체)
        if (keyword != null && !keyword.isBlank()) {
            ObjectNode mm = objectMapper.createObjectNode();
            mm.put("query", keyword);
            ArrayNode fields = mm.putArray("fields");
            fields.add("productName^2").add("keywords").add("description");
            ObjectNode multiMatch = objectMapper.createObjectNode();
            multiMatch.set("multi_match", mm);
            bool.set("must", multiMatch);
        } else {
            ObjectNode matchAll = objectMapper.createObjectNode();
            matchAll.set("match_all", objectMapper.createObjectNode());
            bool.set("must", matchAll);
        }

        // filter: 카테고리 / 가격
        ArrayNode filters = objectMapper.createArrayNode();
        if (categoryIds != null && !categoryIds.isEmpty()) {
            ObjectNode terms = objectMapper.createObjectNode();
            ArrayNode ids = terms.putArray("categoryId");
            categoryIds.forEach(id -> ids.add(id.longValue()));
            ObjectNode termsWrap = objectMapper.createObjectNode();
            termsWrap.set("terms", terms);
            filters.add(termsWrap);
        }
        if (minPrice != null || maxPrice != null) {
            ObjectNode price = objectMapper.createObjectNode();
            if (minPrice != null) price.put("gte", minPrice.intValue());
            if (maxPrice != null) price.put("lte", maxPrice.intValue());
            ObjectNode range = objectMapper.createObjectNode();
            range.set("price", price);
            ObjectNode rangeWrap = objectMapper.createObjectNode();
            rangeWrap.set("range", range);
            filters.add(rangeWrap);
        }
        if (!filters.isEmpty()) {
            bool.set("filter", filters);
        }

        ObjectNode query = objectMapper.createObjectNode();
        query.set("bool", bool);
        root.set("query", query);
        return root;
    }
}
