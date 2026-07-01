package com.hinsight.product.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hinsight.product.es.ProductEsConstants;
import lombok.RequiredArgsConstructor;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
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
    private final RestClient restClient;
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
        term.put("analyzer", "standard");    // 한글 오타 단어를 통째로 비교(형태소 분해로 쪼개지 않도록)
        term.put("suggest_mode", "missing"); // 색인에 없는 토큰만 교정
        term.put("min_word_length", 2);      // 기본값 4는 짧은 한글 단어를 제외하므로 낮춤
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

    private ObjectNode buildQuery(String keyword, List<Long> categoryIds, Integer minPrice, Integer maxPrice) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", MAX_RESULTS);
        root.put("_source", false);

        ObjectNode bool = objectMapper.createObjectNode();

        // must: 키워드 매칭 (없으면 전체)
        if (keyword != null && !keyword.isBlank()) {
            ObjectNode mm = objectMapper.createObjectNode();
            mm.put("query", keyword);
            mm.put("operator", "and"); // 질의어의 모든 토큰이 한 필드 안에서 매칭돼야 함(정밀도↑)
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
