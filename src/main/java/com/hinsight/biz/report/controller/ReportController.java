package com.hinsight.biz.report.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
import com.hinsight.biz.report.service.ReportFeedbackService;
import com.hinsight.biz.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.hinsight.security.userdetails.BizUserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Tag(name = "report-controller", description = "주간 리포트 / 노션 양방향 컨트롤러")
@RestController
@RequiredArgsConstructor
@RequestMapping("/biz/reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportFeedbackService feedbackService;
    private final BizUserDao bizUserDao;

    @PostMapping(value = "/send/notion", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "노션 발송",
            description = "캡처한 대시보드 이미지를 받아, 리포트 블록 + 이미지를 노션 페이지에 첨부한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발송 시도 완료"),
            @ApiResponse(responseCode = "401", description = "미인증(기업 로그인 필요)")
    })
    public ResponseEntity<?> sendNotion(
            @Parameter(description = "캡처한 대시보드 이미지 (PNG)", required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestParam("image") MultipartFile image,
            @Parameter(hidden = true) @AuthenticationPrincipal BizUserDetails principal) {
        return dispatch(principal, image, true);
    }

    @PostMapping(value = "/send/mail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "메일 발송",
            description = "캡처한 대시보드 이미지를 받아, 노션과 동일한 리포트 내용을 메일(본문 + 인라인 이미지)로 발송한다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "발송 시도 완료"),
            @ApiResponse(responseCode = "401", description = "미인증(기업 로그인 필요)")
    })
    public ResponseEntity<?> sendMail(
            @Parameter(description = "캡처한 대시보드 이미지 (PNG)", required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestParam("image") MultipartFile image,
            @Parameter(hidden = true) @AuthenticationPrincipal BizUserDetails principal) {
        return dispatch(principal, image, false);
    }

    // 실제 발송은 @Async 라 202 만 즉시 반환. 이미지 바이트는 async 경계 전에 여기서 읽는다.
    private ResponseEntity<?> dispatch(BizUserDetails principal, MultipartFile image, boolean notionChannel) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "message", "로그인이 필요합니다."));
        }
        BizUser user = bizUserDao.findNotionTargetByBizId(principal.getBizId());
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "기업 정보를 찾을 수 없습니다."));
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            log.error("리포트 발송 실패(이미지 읽기)", e);
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "이미지를 읽지 못했습니다."));
        }

        String message;
        if (notionChannel) {
            reportService.sendToNotion(user, bytes);
            message = "노션 발송 요청 완료";
        } else {
            reportService.sendToMail(user, bytes);
            message = "메일 발송 요청 완료";
        }
        return ResponseEntity.accepted().body(Map.of(
                "ok", true, "company", user.getCompanyName(), "message", message
        ));
    }

    @ResponseBody
    @GetMapping("/feedback")
    @Operation(summary = "노션 피드백 조회 (read-back)",
            description = "로그인 기업 노션 페이지의 to-do(체크박스) 상태와 코멘트를 읽어온다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "노션 설정/페이지 미연결"),
            @ApiResponse(responseCode = "401", description = "미인증(기업 로그인 필요)")
    })
    public ResponseEntity<?> feedback(@Parameter(hidden = true) @AuthenticationPrincipal BizUserDetails principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false, "message", "로그인이 필요합니다."));
        }
        try {
            ReportFeedbackService.Feedback fb = feedbackService.getFeedback(principal.getBizId());
            return ResponseEntity.ok(Map.of(
                    "ok", true, "company", fb.companyName(),
                    "todos", fb.todos(), "comments", fb.comments()
            ));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("노션 피드백 조회 실패", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("ok", false, "message", "노션 조회 중 오류가 발생했습니다."));
        }
    }
}
