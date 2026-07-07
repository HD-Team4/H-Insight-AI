package com.hinsight.biz.reviewanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.exception.custom.biz.MartLoadException;
import com.hinsight.exception.custom.biz.ProductAccessDeniedException;
import com.hinsight.product.model.dto.ProductDetailDto;
import com.hinsight.product.service.ProductService;
import com.hinsight.review.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 상품 판매 개선 자동화 — mart/product-boost/latest.json 을 읽어 검색 없음/클릭 없음/구매 없음
 * 케이스를 골라 승인 페이지와 주간 리포트에 노출하고, 승인 시 실제 상품에 반영한다.
 * 모든 조회와 반영은 로그인한 기업(bizId)이 소유한 상품으로만 제한한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductBoostAutomationService {

    private static final String MARKETING_COPY_SYSTEM = """
            너는 2026년 한국 온라인 패션 커머스의 MD 카피라이터다.
            목적은 클릭률과 구매전환을 높이는 상품명/상세 설명 초안을 만드는 것이다.
            반드시 제공된 상품 정보만 근거로 쓰고, 소재·기능·원산지·할인·재고·배송·브랜드 스토리는 지어내지 마라.

            트렌드 반영 기준:
            - 원문 상품명은 소재, 품목, 색상, 핏 같은 핵심 단서로만 활용하고 문장 구조는 자유롭게 바꿔라.
            - 상품 정체성을 해치지 않는 선에서 데일리, 미니멀, 레이어링, 시티 캐주얼, 빈티지 무드, 원마일웨어, 뉴트럴톤, 시즌리스 같은 트렌드 맥락을 적극 반영하라.
            - 상품명은 검색 키워드 나열이 아니라 클릭하고 싶은 에디토리얼형 이름으로 만든다.
            - 필요하면 원문의 브랜드/시리즈/괄호/대괄호 표현은 과감히 덜어내고 고객 가치가 앞에 오게 재배열한다.
            - 상세 설명은 기존 설명을 보존하거나 덧붙이지 말고, 고객 상세페이지 첫 문단처럼 새로 쓴다.
            - 착용 장면, 스타일링 방법, 실루엣, 촉감/무드, 구매 이유가 한눈에 들어오게 쓴다.

            출력은 JSON 하나만 반환한다. 코드블록, 설명, 마크다운 금지.
            JSON 필드:
            {
              "productName": "클릭을 유도하는 개선 상품명",
              "description": "상세페이지에 바로 넣을 수 있는 한국어 상품 설명",
              "headline": "제안 제목",
              "subHeadline": "제안 요약",
              "supplementalCopy": "변경 방향 한두 문장"
            }
            """;

    private static final String BUCKET = "hf4-datalake";
    private static final String BOOST_MART_KEY = "mart/product-boost/latest.json";
    private static final String BOOST_DUMMY = "dummy/product-boost.json";
    private static final String SALES_MART_KEY = "mart/products/sales.json";
    private static final String SALES_DUMMY = "dummy/sales.json";
    private static final String SALES_PERIOD = "1m";
    private static final long GENERATED_COPY_CACHE_TTL_MILLIS = 12 * 60 * 60 * 1000L;
    private static final long MART_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
    private final ObjectMapper objectMapper;
    private final S3Client s3Client;
    private final ProductService productService;
    private final ReviewAnalysisService reviewAnalysisService;
    private final ReviewService reviewService;
    private final ChatClient chatClient;
    private final Map<String, CachedGeneratedCopy> generatedCopyCache = new ConcurrentHashMap<>();
    private final Map<String, CachedMart> martCache = new ConcurrentHashMap<>();

    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    public JsonNode getBoostMart() {
        return loadMart(BOOST_MART_KEY, BOOST_DUMMY, "상품 판매 개선");
    }

    private JsonNode getSalesMart() {
        return loadMart(SALES_MART_KEY, SALES_DUMMY, "상품 매출");
    }

    private JsonNode loadMart(String s3Key, String dummyResource, String label) {
        CachedMart cached = martCache.get(s3Key);
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMillis() <= MART_CACHE_TTL_MILLIS) {
            return cached.data();
        }
        try {
            byte[] bytes = s3Client.getObjectAsBytes(req -> req.bucket(BUCKET).key(s3Key)).asByteArray();
            JsonNode data = objectMapper.readTree(bytes);
            martCache.put(s3Key, new CachedMart(data, System.currentTimeMillis()));
            return data;
        } catch (Exception e) {
            log.warn("{} mart 로드 실패({}), 클래스패스 dummy로 폴백: {}", label, s3Key, e.getMessage());
            try (InputStream is = new ClassPathResource(dummyResource).getInputStream()) {
                JsonNode data = objectMapper.readTree(is);
                martCache.put(s3Key, new CachedMart(data, System.currentTimeMillis()));
                return data;
            } catch (IOException io) {
                throw new MartLoadException(io);
            }
        }
    }

    // 리포트용 — 주간 매출 하락 상품마다 실제 지표(검색/클릭/구매)로 병목 단계를 판정해 개선 액션을 제안한다.
    public List<BoostSuggestion> plungingSuggestions(JsonNode plunging, Long bizId) {
        Set<Long> owned = productService.getOwnedProductIds(bizId);
        JsonNode byProduct = getBoostMart().path("byProduct");
        List<BoostSuggestion> result = new ArrayList<>();
        if (!plunging.isArray()) return result;

        for (JsonNode row : plunging) {
            long productId = row.path("productId").asLong(0);
            if (productId <= 0 || !owned.contains(productId)) {
                continue;
            }

            JsonNode metrics = byProduct.path(String.valueOf(productId));
            BoostCase boostCase = toCase(productId, classifyState(metrics), metrics, false);
            result.add(new BoostSuggestion(boostCase, row.path("revenue").asLong(0), row.path("growth").asInt(0)));
            if (result.size() >= 5) {
                break;
            }
        }
        return result;
    }

    // 검색 → 클릭 → 구매 여정에서 어느 단계가 막혔는지 실제 지표로 판정. 지표가 없으면(활동 0) 노출부터.
    private String classifyState(JsonNode metrics) {
        long searches = metrics.path("searches").asLong(0);
        long clicks = metrics.path("clicks").asLong(0);
        long purchases = metrics.path("purchases").asLong(0);
        if (searches == 0) return "NO_SEARCH";     // 노출 자체가 없음
        if (clicks == 0) return "NO_CLICK";        // 검색은 되나 클릭 없음 → 상품명 개선
        if (purchases == 0) return "NO_PURCHASE";  // 클릭은 되나 구매 없음 → 상세설명 개선
        // 세 단계 모두 활동은 있으나 하락 → 클릭률(ctr)·구매전환(cvr) 중 더 약한 단계를 개선
        return metrics.path("cvr").asDouble(0) <= metrics.path("ctr").asDouble(0) ? "NO_PURCHASE" : "NO_CLICK";
    }

    // 액션 페이지·apply 공용 — 소유 상품이 아니면 예외
    public BoostCase getCase(String caseType, Long productId, Long bizId) {
        return getCase(caseType, productId, bizId, true);
    }

    public BoostCase getPreparedCase(String caseType, Long productId, Long bizId) {
        return getCase(caseType, productId, bizId, false);
    }

    public boolean needsAsyncGeneration(String caseType, Long productId, Long bizId) {
        requireOwned(productId, bizId);
        String normalized = normalizeCaseType(caseType);
        if (!requiresGeneratedCopy(normalized)) {
            return false;
        }
        ProductDetailDto product = findProduct(productId);
        String productName = product == null ? "" : product.productName();
        return cachedGeneratedCopy(generatedCopyCacheKey(normalized, product, productName)) == null;
    }

    public void prepareGeneratedCopy(String caseType, Long productId, Long bizId) {
        String normalized = normalizeCaseType(caseType);
        if (!requiresGeneratedCopy(normalized)) {
            requireOwned(productId, bizId);
            return;
        }
        if ("NO_PURCHASE".equals(normalized)) {
            buildCopywritingDraft(productId, bizId);
        } else {
            getCase(normalized, productId, bizId, true);
        }
    }

    private BoostCase getCase(String caseType, Long productId, Long bizId, boolean generateCopy) {
        requireOwned(productId, bizId);
        String normalized = normalizeCaseType(caseType);
        JsonNode metrics = getBoostMart().path("byProduct").path(String.valueOf(productId));
        return toCase(productId, normalized, metrics, generateCopy);
    }

    public boolean apply(String caseType, Long productId, Long bizId, String value) {
        BoostCase item = getCase(caseType, productId, bizId);
        return switch (item.caseType()) {
            case "NO_SEARCH" -> productService.promoteProductForBiz(item.productId(), bizId);
            case "NO_CLICK" -> {
                String productName = value != null && !value.isBlank() ? value : item.generatedProductName();
                yield productService.updateProductNameForBiz(item.productId(), bizId, productName);
            }
            case "NO_PURCHASE" -> {
                String description = value != null && !value.isBlank() ? value : item.generatedDescription();
                yield productService.updateDescriptionForBiz(item.productId(), bizId, description);
            }
            default -> false;
        };
    }

    // NO_PURCHASE 전용 상세 초안 — LLM 으로 상품 자체의 구매 설득 문구를 생성한다.
    public CopywritingDraft buildCopywritingDraft(Long productId, Long bizId) {
        return buildCopywritingDraft(productId, bizId, true);
    }

    public CopywritingDraft buildPreparedCopywritingDraft(Long productId, Long bizId) {
        return buildCopywritingDraft(productId, bizId, false);
    }

    private CopywritingDraft buildCopywritingDraft(Long productId, Long bizId, boolean generateCopy) {
        BoostCase item = getCase("NO_PURCHASE", productId, bizId, false);

        double targetRating = reviewService.getAverageRatingByProductId(productId);

        Map<Long, JsonNode> salesByProduct = indexByProductId(getSalesMart().path(SALES_PERIOD));
        JsonNode targetSales = salesByProduct.get(productId);

        JsonNode details = reviewAnalysisService.getProductAnalysis().path("details");
        JsonNode targetDetail = details.path(String.valueOf(productId));

        ProductDetailDto targetProduct = findProduct(productId);
        GeneratedCopy copy = generatedCopyFor(targetProduct, item.productName(), item.category(), targetDetail, null, "NO_PURCHASE", generateCopy);
        String headline = firstNonBlank(copy.headline(), "‘" + item.productName() + "’ 상세 설명 개선 제안");
        String subHeadline = firstNonBlank(copy.subHeadline(), "트렌드와 상품 강점을 반영해 구매 설득 문구를 새로 생성했습니다.");
        String description = firstNonBlank(copy.description(), fallbackDetailSuggestion(targetProduct));
        String supplement = firstNonBlank(copy.supplementalCopy(), "상품 자체의 핏, 활용도, 스타일링 장점을 중심으로 상세 설명을 보강합니다.");

        return new CopywritingDraft(
                productId, item.productName(), item.category(),
                asIntOrNull(targetSales, "growth"), asLongOrZero(targetSales, "revenue"),
                targetRating > 0 ? round1(targetRating) : null,
                targetProduct == null ? "" : nullToBlank(targetProduct.description()),
                headline, subHeadline, supplement, description,
                List.of("승인 시 상품 상세 설명이 아래 내용으로 즉시 교체됩니다.",
                        "필요하면 적용 전 내용을 직접 수정할 수 있습니다."));
    }

    private Double round1(Double value) {
        if (value == null) return null;
        return Math.round(value * 10.0) / 10.0;
    }

    private Map<Long, JsonNode> indexByProductId(JsonNode rows) {
        Map<Long, JsonNode> byId = new HashMap<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                byId.put(row.path("productId").asLong(), row);
            }
        }
        return byId;
    }

    private List<String> signalSummaries(JsonNode detail) {
        List<String> result = new ArrayList<>();
        if (detail == null) return result;
        for (JsonNode signal : detail.path("aiStrategy").path("signals")) {
            String name = signal.path("name").asText("");
            String text = signal.path("detail").asText("");
            if (!text.isBlank()) {
                result.add(name.isBlank() ? text : name + " — " + text);
            }
        }
        return result;
    }

    private List<String> toStringList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode v : arrayNode) result.add(v.asText());
        }
        return result;
    }

    private Integer asIntOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asInt();
    }

    private Long asLongOrZero(JsonNode node, String field) {
        return node == null ? 0L : node.path(field).asLong(0);
    }

    private void requireOwned(Long productId, Long bizId) {
        if (productId == null || !productService.getOwnedProductIds(bizId).contains(productId)) {
            throw new ProductAccessDeniedException();
        }
    }

    // byProduct 지표(없으면 MissingNode → 0)로 케이스를 구성. 상품명/이미지는 상품 상세로 보완.
    private BoostCase toCase(long productId, String caseType, JsonNode metrics, boolean generateCopy) {
        ProductDetailDto product = findProduct(productId);
        String productName = firstNonBlank(metrics.path("productName").asText(null),
                product == null ? "" : product.productName());
        String category = firstNonBlank(metrics.path("category").asText(null), "");
        GeneratedCopy generated = generatedCopyFor(product, productName, category, null, metrics, caseType, generateCopy);

        return new BoostCase(
                caseType,
                label(caseType),
                reason(caseType),
                productId,
                productName,
                category,
                product == null ? "" : product.imageUrl(),
                metrics.path("searches").asLong(0),
                metrics.path("clicks").asLong(0),
                metrics.path("purchases").asLong(0),
                firstNonBlank(generated.productName(), fallbackProductNameSuggestion(productName)),
                firstNonBlank(generated.description(), fallbackDetailSuggestion(product)),
                actionUrl(caseType, productId)
        );
    }

    private ProductDetailDto findProduct(Long productId) {
        if (productId == null || productId <= 0) return null;
        try {
            return productService.getProductDetailById(productId);
        } catch (Exception e) {
            return null;
        }
    }

    private GeneratedCopy generateMarketingCopy(ProductDetailDto product, String productName, String category,
                                                JsonNode analysisDetail, JsonNode caseRow, String caseType) {
        String currentName = firstNonBlank(productName, product == null ? "" : product.productName());
        if (currentName.isBlank()) return GeneratedCopy.empty();
        String cacheKey = generatedCopyCacheKey(caseType, product, currentName);

        String user = """
                생성 목적: %s

                상품 정보:
                - 현재 상품명: %s
                - 카테고리: %s
                - 가격: %s원
                - 현재 설명: %s
                - 상품 정보: %s

                방문 지표:
                - 검색수: %s
                - 클릭수: %s
                - 구매수: %s
                - CTR: %s
                - CVR: %s

                리뷰/분석 신호:
                - 키워드: %s
                - 보완할 불만/부족 정보: %s
                - 전략 신호: %s

                작성 지시:
                - productName: 현재 상품명의 핵심 단서는 반영하되, 문장 구조와 표현은 유연하게 바꿔라. 품목/색상도 상품 정체성이 유지되면 자연스러운 표현으로 바꿔도 된다.
                - productName은 18~38자 권장. 고객 가치나 무드를 앞에 배치하고, 검색 키워드는 자연스럽게 녹여라.
                - productName 예시 톤: "시티 무드 오버핏 코튼 셔츠 - 애쉬", "레이어드하기 좋은 그래픽 티셔츠 - 블루", "뉴트럴 데일리 와이드 데님 - 브라운"
                - description: 기존 설명을 재사용하지 말고 3~5문장으로 새로 써라. 첫 문장은 이 상품을 입는 장면이나 무드로 시작하라.
                - description에는 스타일링 조합, 착용감, 실루엣, 계절/일상 활용도를 최소 2개 이상 포함하라.
                - NO_CLICK이면 productName 품질을 가장 우선하고, NO_PURCHASE이면 description 품질을 가장 우선하라.
                - "같은 카테고리 상품 참고"라는 표현을 쓰지 마라.
                - 이모지, 따옴표 장식, 마크다운, 과장된 최상급 표현, "요즘 핫한"이라는 문구 자체는 금지.
                """.formatted(
                caseType,
                currentName,
                nullToBlank(category),
                product == null || product.price() == null ? "-" : product.price(),
                product == null ? "" : nullToBlank(product.description()),
                product == null ? "" : nullToBlank(product.productInfo()),
                caseRow == null ? 0 : caseRow.path("searches").asLong(0),
                caseRow == null ? 0 : caseRow.path("clicks").asLong(0),
                caseRow == null ? 0 : caseRow.path("purchases").asLong(0),
                caseRow == null ? 0 : caseRow.path("ctr").asDouble(0),
                caseRow == null ? 0 : caseRow.path("cvr").asDouble(0),
                toStringList(analysisDetail == null ? null : analysisDetail.path("keywords")),
                toStringList(analysisDetail == null ? null : analysisDetail.path("painPoints")),
                signalSummaries(analysisDetail));

        try {
            String content = chatClient.prompt()
                    .system(MARKETING_COPY_SYSTEM)
                    .user(user)
                    .call()
                    .content();
            JsonNode json = objectMapper.readTree(extractJson(content));
            String generatedName = cleanSingleLine(json.path("productName").asText(""));
            if (tooSimilarName(generatedName, currentName)) {
                generatedName = "";
            }
            return new GeneratedCopy(
                    generatedName,
                    cleanMultiline(json.path("description").asText("")),
                    cleanSingleLine(json.path("headline").asText("")),
                    cleanSingleLine(json.path("subHeadline").asText("")),
                    cleanMultiline(json.path("supplementalCopy").asText(""))
            ).cacheIn(generatedCopyCache, cacheKey);
        } catch (Exception e) {
            log.warn("[판매개선] LLM 카피 생성 실패 product={} caseType={}: {}", currentName, caseType, e.getMessage());
            return GeneratedCopy.empty();
        }
    }

    private GeneratedCopy generatedCopyFor(ProductDetailDto product, String productName, String category,
                                           JsonNode analysisDetail, JsonNode caseRow,
                                           String caseType, boolean generateCopy) {
        if (!requiresGeneratedCopy(caseType)) {
            return GeneratedCopy.empty();
        }
        String currentName = firstNonBlank(productName, product == null ? "" : product.productName());
        if (currentName.isBlank()) return GeneratedCopy.empty();

        GeneratedCopy cached = cachedGeneratedCopy(generatedCopyCacheKey(caseType, product, currentName));
        if (cached != null) {
            return cached;
        }
        return generateCopy
                ? generateMarketingCopy(product, currentName, category, analysisDetail, caseRow, caseType)
                : GeneratedCopy.empty();
    }

    private boolean requiresGeneratedCopy(String caseType) {
        return "NO_CLICK".equals(caseType) || "NO_PURCHASE".equals(caseType);
    }

    private String generatedCopyCacheKey(String caseType, ProductDetailDto product, String productName) {
        String productKey = product != null && product.productId() != null
                ? String.valueOf(product.productId())
                : normalizeForCompare(productName);
        return normalizeCaseType(caseType) + ":" + productKey;
    }

    private GeneratedCopy cachedGeneratedCopy(String cacheKey) {
        CachedGeneratedCopy cached = generatedCopyCache.get(cacheKey);
        if (cached == null) return null;
        if (System.currentTimeMillis() - cached.cachedAtMillis() > GENERATED_COPY_CACHE_TTL_MILLIS) {
            generatedCopyCache.remove(cacheKey);
            return null;
        }
        return cached.copy();
    }

    private String extractJson(String value) {
        if (value == null) return "{}";
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end >= start ? value.substring(start, end + 1) : "{}";
    }

    private String cleanSingleLine(String value) {
        return nullToBlank(value).replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }

    private String cleanMultiline(String value) {
        String cleaned = nullToBlank(value).replace("\r", "").trim();
        return cleaned.replaceAll("\\n{3,}", "\n\n");
    }

    // 생성된 상품명이 원본과 거의 같으면(앞부분이 대부분 일치) 제안하지 않는다.
    private boolean tooSimilarName(String generated, String original) {
        String g = normalizeForCompare(generated);
        String o = normalizeForCompare(original);
        if (g.isBlank() || o.isBlank()) return false;
        if (g.equals(o)) return true;
        int shorter = Math.min(g.length(), o.length());
        int common = 0;
        for (int i = 0; i < shorter; i++) {
            if (g.charAt(i) == o.charAt(i)) common++;
        }
        return shorter >= 12 && common >= shorter * 0.85;
    }

    private String normalizeForCompare(String value) {
        return nullToBlank(value).toLowerCase().replaceAll("[^0-9a-z가-힣]", "");
    }

    private String fallbackProductNameSuggestion(String targetName) {
        return targetName == null ? "" : targetName.trim();
    }

    private String fallbackDetailSuggestion(ProductDetailDto target) {
        if (target == null) return "";
        return target.description() == null || target.description().isBlank()
                ? target.productName() + "의 특징을 자연스럽게 보여주는 상품 설명입니다."
                : target.description().trim();
    }

    // 메일/노션 리포트에 넣는 승인 링크
    public String actionUrl(String caseType, long productId) {
        return UriComponentsBuilder.fromUriString(trimTrailingSlash(publicBaseUrl))
                .path("/biz/review-analysis/boost/action")
                .queryParam("caseType", caseType)
                .queryParam("productId", productId)
                .build()
                .toUriString();
    }

    private String normalizeCaseType(String caseType) {
        if (caseType == null || caseType.isBlank()) return "NO_SEARCH";
        return caseType.trim().toUpperCase();
    }

    private String label(String caseType) {
        return switch (caseType) {
            case "NO_SEARCH" -> "검색이 없는 상품";
            case "NO_CLICK" -> "검색은 있는데 클릭이 없는 상품";
            case "NO_PURCHASE" -> "클릭은 있는데 구매가 없는 상품";
            default -> "판매 개선 대상";
        };
    }

    private String reason(String caseType) {
        return switch (caseType) {
            case "NO_SEARCH" -> "메인 화면 라이브 영상 옆에 상품을 띄워 검색 유입을 만듭니다.";
            case "NO_CLICK" -> "트렌드와 상품 강점을 반영한 새 상품명으로 클릭을 유도합니다.";
            case "NO_PURCHASE" -> "트렌드와 리뷰 신호를 반영한 상세 설명으로 구매 결정을 돕습니다.";
            default -> "상품 판매 흐름을 개선합니다.";
        };
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "http://localhost:8080";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record GeneratedCopy(String productName, String description, String headline,
                                 String subHeadline, String supplementalCopy) {
        static GeneratedCopy empty() {
            return new GeneratedCopy("", "", "", "", "");
        }

        GeneratedCopy cacheIn(Map<String, CachedGeneratedCopy> cache, String cacheKey) {
            if (hasAny()) {
                cache.put(cacheKey, new CachedGeneratedCopy(this, System.currentTimeMillis()));
            }
            return this;
        }

        private boolean hasAny() {
            return !productName.isBlank()
                    || !description.isBlank()
                    || !headline.isBlank()
                    || !subHeadline.isBlank()
                    || !supplementalCopy.isBlank();
        }
    }

    private record CachedGeneratedCopy(GeneratedCopy copy, long cachedAtMillis) {
    }

    private record CachedMart(JsonNode data, long cachedAtMillis) {
    }

    public record BoostCase(
            String caseType,
            String label,
            String reason,
            Long productId,
            String productName,
            String category,
            String imageUrl,
            Long searches,
            Long clicks,
            Long purchases,
            String generatedProductName,
            String generatedDescription,
            String actionUrl
    ) {
    }

    public record BoostSuggestion(
            BoostCase item,
            Long revenue,
            Integer growth
    ) {
    }

    public record CopywritingDraft(
            Long targetProductId,
            String targetProductName,
            String targetCategory,
            Integer targetGrowth,
            Long targetRevenue,
            Double targetRating,
            String currentDescription,
            String headline,
            String subHeadline,
            String supplementalCopy,
            String generatedDescription,
            List<String> automationSteps
    ) {
    }
}
