package com.hinsight.product.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.dao.ProductKeywordDao;
import com.hinsight.product.es.ProductEsConstants;
import com.hinsight.product.model.es.ProductDocument;
import com.hinsight.product.model.vo.Product;
import com.hinsight.product.model.vo.ProductKeyword;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 상품 인덱스 생성 및 (재)색인.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexService {

    private static final int BULK_CHUNK = 500;

    private final ElasticsearchClient esClient;
    private final ProductDao productDao;
    private final ProductKeywordDao productKeywordDao;
    private final SynonymSyncService synonymSyncService;

    /** 인덱스가 없으면 (동의어 세트 → 인덱스 순으로) 생성한다. 이미 있으면 false. */
    public boolean createIndexIfAbsent() throws IOException {
        boolean exists = esClient.indices().exists(e -> e.index(ProductEsConstants.INDEX)).value();
        if (exists) {
            return false;
        }
        // 인덱스가 synonyms_set 을 참조하므로 세트를 먼저 만들어 둔다.
        synonymSyncService.syncToEs();
        try (InputStream is = new ClassPathResource(ProductEsConstants.INDEX_DEFINITION).getInputStream()) {
            esClient.indices().create(c -> c.index(ProductEsConstants.INDEX).withJson(is));
        }
        log.info("[ES] 인덱스 생성 완료: {}", ProductEsConstants.INDEX);
        return true;
    }

    /** 전체 상품을 ES에 재색인한다. 색인 건수를 반환. */
    public int reindexAll() throws IOException {
        createIndexIfAbsent();

        List<Product> products = productDao.getAllProducts();
        Map<Long, List<String>> keywordMap = productKeywordDao.findAll().stream()
                .collect(Collectors.groupingBy(
                        ProductKeyword::getProductId,
                        Collectors.mapping(ProductKeyword::getKeyword, Collectors.toList())));

        int total = 0;
        for (List<Product> chunk : partition(products)) {
            BulkRequest.Builder br = new BulkRequest.Builder();
            for (Product p : chunk) {
                ProductDocument doc = toDocument(p, keywordMap.getOrDefault(p.getProductId(), List.of()));
                br.operations(op -> op.index(idx -> idx
                        .index(ProductEsConstants.INDEX)
                        .id(String.valueOf(doc.getProductId()))
                        .document(doc)));
            }
            BulkResponse res = esClient.bulk(br.build());
            if (res.errors()) {
                res.items().stream()
                        .filter(i -> i.error() != null)
                        .findFirst()
                        .ifPresent(i -> log.warn("[ES] bulk 색인 오류 예시: {}", i.error().reason()));
            }
            total += chunk.size();
        }
        esClient.indices().refresh(r -> r.index(ProductEsConstants.INDEX));
        log.info("[ES] 재색인 완료: {} docs", total);
        return total;
    }

    private ProductDocument toDocument(Product p, List<String> keywords) {
        ProductDocument doc = new ProductDocument();
        doc.setProductId(p.getProductId());
        doc.setCategoryId(p.getCategoryId());
        doc.setPrice(p.getPrice() == null ? null : p.getPrice().doubleValue());
        doc.setProductName(p.getProductName());
        doc.setDescription(p.getDescription());
        doc.setKeywords(keywords);
        return doc;
    }

    private List<List<Product>> partition(List<Product> list) {
        List<List<Product>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += BULK_CHUNK) {
            parts.add(list.subList(i, Math.min(i + BULK_CHUNK, list.size())));
        }
        return parts;
    }
}
