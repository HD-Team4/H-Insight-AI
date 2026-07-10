package com.hinsight.biz.report.service;

import com.hinsight.ai.mcp.notion.NotionClient;
import com.hinsight.ai.mcp.notion.NotionProperties;
import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
import com.hinsight.exception.ErrorCode;
import com.hinsight.exception.custom.notion.NotionIntegrationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

// 노션 read-back: 기업 페이지의 to-do 상태와 코멘트를 읽어와 반환.
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportFeedbackService {

    private final NotionProperties notionProps;
    private final NotionClient notionClient;
    private final BizUserDao bizUserDao;

    public Feedback getFeedback(Long bizId) {
        if (!notionProps.isConfigured()) {
            throw new NotionIntegrationException(ErrorCode.NOTION_NOT_CONFIGURED);
        }
        BizUser target = bizUserDao.findNotionTargetByBizId(bizId);
        if (target == null || target.getNotionPageId() == null || target.getNotionPageId().isBlank()) {
            throw new NotionIntegrationException(ErrorCode.NOTION_PAGE_NOT_LINKED);
        }

        String pageId = target.getNotionPageId();
        List<Map<String, Object>> todos = notionClient.getTodos(pageId);
        List<Map<String, Object>> comments = notionClient.getComments(pageId);

        long done = todos.stream().filter(t -> Boolean.TRUE.equals(t.get("checked"))).count();
        log.info("[read-back] company={} to-do {}/{} 완료, 코멘트 {}건",
                target.getCompanyName(), done, todos.size(), comments.size());

        return new Feedback(target.getCompanyName(), todos, comments);
    }

    public record Feedback(String companyName,
                           List<Map<String, Object>> todos,
                           List<Map<String, Object>> comments) {
    }
}
