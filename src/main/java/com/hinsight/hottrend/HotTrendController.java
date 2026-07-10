package com.hinsight.hottrend;

import com.hinsight.product.dao.ProductDao;
import com.hinsight.product.model.vo.Product;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 초실시간 급상승 랭킹 API — 메인 화면 위젯이 폴링한다.
 * <p>랭킹 산출(어떤 상품이 몇 위인지)은 Redis 스피드 레이어가 담당하고,
 * 화면에 뿌릴 카드 정보(이미지·가격·이름)만 상위 소수 상품에 대해 상품 DB에서 조회해 결합한다.
 * (전체 이벤트 GROUP BY 가 아니라 상위 ~10건 PK 조회라 DB 부하는 무시할 수준)
 * <p>/api/** 는 고객/기업 시큐리티 체인에 매칭되지 않아 별도 인가 없이 접근 가능(공개 랭킹).
 */
@Tag(name = "hot-trend-controller", description = "초실시간 급상승 랭킹 (Kafka→Redis 스피드 레이어)")
@RestController
@RequiredArgsConstructor
public class HotTrendController {

    private final HotTrendService hotTrend;
    private final ProductDao productDao;

    @Operation(summary = "실시간 급상승 TOP10",
            description = "최근 60분 조회량·판매량 급상승 상품 TOP10을 상품 카드(이미지·가격 포함)로 반환")
    @GetMapping("/api/hot-trends")
    public Map<String, Object> hotTrends() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("views", cards(hotTrend.top("view", 15)));   // 조회량 TOP10
        out.put("sales", cards(hotTrend.top("sale", 15)));   // 판매량 TOP10
        out.put("generatedAt", Instant.now().toString());
        return out;
    }

    /** Redis 랭킹(rank·productId·count) + 상품 DB(이미지·가격·이름) 결합 → 상품 카드 최대 10개(순위 유지). */
    private List<Map<String, Object>> cards(List<Map<String, Object>> ranked) {
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<Long> ids = ranked.stream()
                .map(r -> ((Number) r.get("productId")).longValue())
                .collect(Collectors.toList());
        Map<Long, Product> byId = productDao.findByIds(ids).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity(), (a, b) -> a));

        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (Map<String, Object> r : ranked) {
            long pid = ((Number) r.get("productId")).longValue();
            Product p = byId.get(pid);
            if (p == null || p.getImageUrl() == null) {   // DB에 없거나 이미지 없는 상품은 카드에서 제외
                continue;
            }
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("rank", rank++);
            card.put("productId", pid);
            card.put("name", p.getProductName());
            card.put("price", p.getPrice());
            card.put("imageUrl", p.getImageUrl());
            card.put("count", r.get("count"));
            out.add(card);
            if (out.size() >= 10) {
                break;
            }
        }
        return out;
    }
}
