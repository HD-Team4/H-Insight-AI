package com.hinsight.biz.reviewanalysis.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.hinsight.biz.reviewanalysis.service.ProductBoostAutomationService;
import com.hinsight.biz.reviewanalysis.service.ReviewAnalysisService;
import com.hinsight.security.userdetails.BizUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Slf4j
@Tag(name = "review-analysis-controller", description = "리뷰 분석 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/biz/review-analysis")
public class ReviewAnalysisController {

    private final ReviewAnalysisService reviewAnalysisService;
    private final ProductBoostAutomationService boostAutomationService;

    // 상품 분석 화면 (주간 급등/급락 TOP5 → 클릭 시 통계·리뷰 분석·AI 전략)
    @Operation(summary = "상품 분석 페이지", description = "주간 급등/급락 TOP5 + 리뷰 감성·키워드 + AI 전략 화면")
    @GetMapping
    public String analysisPage() {
        return "biz/reviewanalysis/analysis";
    }

    // 상품 분석 마트 JSON (프론트가 fetch)
    @Operation(summary = "상품 분석 데이터(JSON)", description = "상품 분석 마트 JSON을 반환한다(프론트 fetch용)")
    @ResponseBody
    @GetMapping("/data")
    public JsonNode data() {
        return reviewAnalysisService.getProductAnalysis();
    }

    // 판매 개선 승인 페이지 — 메일/노션 리포트의 승인 링크가 이 화면으로 연결된다.
    @Operation(summary = "판매 개선 승인 페이지",
            description = "검색없음/클릭없음/구매없음 케이스별 AI 제안을 미리보고 승인하는 화면")
    @GetMapping("/boost/action")
    public String boostAction(@RequestParam String caseType,
                              @RequestParam Long productId,
                              @RequestParam(defaultValue = "false") boolean prepared,
                              @AuthenticationPrincipal BizUserDetails principal,
                              Model model) {
        if (principal == null) {
            return "redirect:/biz/login";
        }
        String normalized = caseType == null ? "" : caseType.trim().toUpperCase();
        try {
            if (!prepared && boostAutomationService.needsAsyncGeneration(normalized, productId, principal.getBizId())) {
                model.addAttribute("caseType", normalized);
                model.addAttribute("productId", productId);
                return "biz/reviewanalysis/boost-loading";
            }
            if ("NO_PURCHASE".equals(normalized)) {
                model.addAttribute("draft", prepared
                        ? boostAutomationService.buildPreparedCopywritingDraft(productId, principal.getBizId())
                        : boostAutomationService.buildCopywritingDraft(productId, principal.getBizId()));
                return "biz/reviewanalysis/copywriting";
            }
            model.addAttribute("item", prepared
                    ? boostAutomationService.getPreparedCase(normalized, productId, principal.getBizId())
                    : boostAutomationService.getCase(normalized, productId, principal.getBizId()));
            return "biz/reviewanalysis/boost-action";
        } catch (IllegalArgumentException e) {
            log.warn("[판매개선] 승인 페이지 접근 거부 bizId={} productId={}: {}", principal.getBizId(), productId, e.getMessage());
            return "redirect:/biz/review-analysis";
        }
    }

    @ResponseBody
    @GetMapping("/boost/prepare")
    public ResponseEntity<?> boostPrepare(@RequestParam String caseType,
                                          @RequestParam Long productId,
                                          @AuthenticationPrincipal BizUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "message", "로그인이 필요합니다."));
        }
        try {
            boostAutomationService.prepareGeneratedCopy(caseType, productId, principal.getBizId());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("[판매개선] 제안 생성 실패 bizId={} caseType={} productId={}",
                    principal.getBizId(), caseType, productId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "message", "제안 생성 중 오류가 발생했습니다."));
        }
    }

    // 판매 개선 승인 적용 — 상품명/상세설명 교체 또는 메인 추천 슬롯 노출
    @Operation(summary = "판매 개선 승인 적용", description = "승인한 제안을 실제 상품에 반영한다")
    @ResponseBody
    @PostMapping("/boost/apply")
    public ResponseEntity<?> boostApply(@RequestBody BoostApplyRequest request,
                                        @AuthenticationPrincipal BizUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "message", "로그인이 필요합니다."));
        }
        try {
            boolean applied = boostAutomationService.apply(request.caseType(), request.productId(), principal.getBizId(), request.value());
            return ResponseEntity.ok(Map.of("ok", applied, "message", applied ? "적용했습니다." : "적용에 실패했습니다."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("ok", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("[판매개선] 적용 실패 bizId={} req={}", principal.getBizId(), request, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("ok", false, "message", "적용 중 오류가 발생했습니다."));
        }
    }

    public record BoostApplyRequest(String caseType, Long productId, String value) {
    }
}
