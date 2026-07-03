package com.hinsight.biz.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.ai.mcp.notion.NotionClient;
import com.hinsight.ai.mcp.notion.NotionProperties;
import com.hinsight.biz.auth.model.vo.BizUser;
import com.hinsight.biz.dashboard.service.DashboardService;
import com.hinsight.biz.report.notify.MailNotifier;
import com.hinsight.biz.report.service.DashboardReportBuilder.ReportContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 대시보드 JSON 을 리포트로 조립해 노션/메일로 발송. 채널별 독립 try/catch.
@Slf4j
@Service
public class ReportService {

    private final DashboardService dashboardService;
    private final DashboardReportBuilder reportBuilder;
    private final NotionClient notionClient;
    private final NotionProperties notionProps;
    private final MailNotifier mailNotifier;
    private final ObjectMapper objectMapper;
    private final String period;

    public ReportService(DashboardService dashboardService,
                         DashboardReportBuilder reportBuilder,
                         NotionClient notionClient,
                         NotionProperties notionProps,
                         MailNotifier mailNotifier,
                         ObjectMapper objectMapper,
                         @Value("${report.period:1w}") String period) {
        this.dashboardService = dashboardService;
        this.reportBuilder = reportBuilder;
        this.notionClient = notionClient;
        this.notionProps = notionProps;
        this.mailNotifier = mailNotifier;
        this.objectMapper = objectMapper;
        this.period = period;
    }

    // 스케줄 발송: 노션 + 메일 (동기, 스케줄러 스레드)
    public DispatchResult sendWeeklyReport(BizUser user) {
        return dispatch(user, null, true, true);
    }

    @Async
    public void sendToNotion(BizUser user, byte[] image) {
        try {
            dispatch(user, image, true, false);
        } catch (Exception e) {
            log.error("[리포트] 노션 비동기 발송 실패 company={}", user.getCompanyName(), e);
        }
    }

    @Async
    public void sendToMail(BizUser user, byte[] image) {
        try {
            dispatch(user, image, false, true);
        } catch (Exception e) {
            log.error("[리포트] 메일 비동기 발송 실패 company={}", user.getCompanyName(), e);
        }
    }

    // image 가 null 이면 블록/본문만(스케줄), 있으면 이미지까지 첨부. 두 채널 동일 컨텐츠.
    private DispatchResult dispatch(BizUser user, byte[] image, boolean doNotion, boolean doMail) {
        String company = user.getCompanyName() != null ? user.getCompanyName() : "기업";
        JsonNode data = dashboardService.getDashboard(period);
        ReportContent content = reportBuilder.build(company, data);

        boolean notion = false, mail = false;

        // 노션: 날짜별 토글 생성 후 그 안에 리포트 블록(+이미지)
        if (doNotion && notionProps.isConfigured()
                && user.getNotionPageId() != null && !user.getNotionPageId().isBlank()) {
            try {
                String toggleId = notionClient.appendToggle(user.getNotionPageId(), content.title());
                notionClient.appendBlocks(toggleId, content.notionBlocks());
                if (image != null && image.length > 0) {
                    notionClient.attachImageToPage(toggleId, null, image, imageName(company), "image/png");
                }
                notion = true;
            } catch (Exception e) {
                log.warn("[리포트] 노션 발송 실패 company={}: {}", company, e.getMessage());
            }
        }

        // 메일: contact_emails 주소로 HTML 본문 발송
        if (doMail) {
            mail = mailNotifier.send(parseEmails(user.getContactEmails()), content.title(),
                    content.htmlBody(), image, imageName(company));
        }

        log.info("[리포트] 발송 완료 company={} (image={}) → notion={}, mail={}",
                company, image != null, notion, mail);
        return new DispatchResult(company, notion, mail);
    }

    private String imageName(String company) {
        return "dashboard-" + company.replaceAll("\\s+", "") + ".png";
    }

    // contact_emails(json 배열) → 이메일 리스트. 실패 시 콤마 분리 폴백.
    private List<String> parseEmails(String contactEmailsJson) {
        if (contactEmailsJson == null || contactEmailsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(contactEmailsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            List<String> fallback = new ArrayList<>();
            for (String s : contactEmailsJson.replaceAll("[\\[\\]\"]", "").split(",")) {
                if (!s.isBlank()) fallback.add(s.trim());
            }
            return fallback;
        }
    }

    public record DispatchResult(String companyName, boolean notion, boolean mail) {
    }
}
