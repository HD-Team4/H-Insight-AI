package com.hinsight.biz.dashboard.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.hinsight.ai.mcp.notion.NotionService;
import com.hinsight.biz.dashboard.service.DashboardService;
import com.hinsight.security.userdetails.BizUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Tag(name = "dashboard-controller", description = "기업 대시보드 컨트롤러")
@Controller
@RequiredArgsConstructor
@RequestMapping("/biz/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final NotionService notionService;

    // 대시보드 화면 (차트는 아래 /data 를 fetch 해서 그림)
    @GetMapping
    @Operation(summary = "기업 대시보드", description = "기업 로그인 성공 후 대시보드 페이지를 렌더링한다")
    public String dashboardPage() {
        return "biz/dashboard/dashboard";
    }


    @ResponseBody
    @GetMapping("/data")
    @Operation(summary = "대시보드 데이터 제공", description = "기간별 집계 데이터 (JSON). 프론트가 탭 전환 시 재요청")
    public JsonNode data(
            @Parameter(description = "집계 기간", schema = @Schema(allowableValues = {"1w", "1m", "6m", "1y"}, defaultValue = "1m"))
            @RequestParam(defaultValue = "1m") String period) {
        return dashboardService.getDashboard(period);
    }


    @ResponseBody
    @PostMapping(value = "/export/notion", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "대시보드 → 노션 전송",
            description = "프론트에서 캡처한 대시보드 이미지를 로그인 기업의 노션 페이지에 첨부한다")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "전송 성공"),
            @ApiResponse(responseCode = "400", description = "이미지 누락/설정 미비/노션 페이지 미연결"),
            @ApiResponse(responseCode = "401", description = "미인증(기업 로그인 필요)"),
            @ApiResponse(responseCode = "502", description = "노션 API 호출 실패(권한/네트워크)")
    })
    public ResponseEntity<?> exportToNotion(
            @Parameter(description = "캡처한 대시보드 이미지 (PNG)", required = true,
                    content = @Content(mediaType = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestParam("image") MultipartFile image,
            @Parameter(hidden = true) @AuthenticationPrincipal BizUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "message", "로그인이 필요합니다."));
        }
        try {
            NotionService.Result result = notionService.sendDashboardImage(principal.getBizId(), image.getBytes());
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", result.companyName() + " 노션 페이지로 전송했습니다.",
                    "notionPageId", result.notionPageId()
            ));
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("노션 전송 실패(설정/입력): {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (IOException e) {
            log.error("노션 전송 실패(이미지 읽기)", e);
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "이미지를 읽지 못했습니다."));
        } catch (Exception e) {
            log.error("노션 전송 실패(노션 API)", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("ok", false, "message", "노션 전송 중 오류가 발생했습니다. 페이지 접근 권한을 확인하세요."));
        }
    }
}
