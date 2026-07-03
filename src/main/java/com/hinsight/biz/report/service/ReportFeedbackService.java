package com.hinsight.biz.report.service;

import com.hinsight.ai.mcp.notion.NotionClient;
import com.hinsight.ai.mcp.notion.NotionProperties;
import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
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
            throw new IllegalStateException("노션 토큰(NOTION_TOKEN)이 설정되지 않았습니다.");
        }
        BizUser target = bizUserDao.findNotionTargetByBizId(bizId);
        if (target == null || target.getNotionPageId() == null || target.getNotionPageId().isBlank()) {
            throw new IllegalStateException("이 계정에 연결된 노션 페이지(notion_page_id)가 없습니다.");
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
