package com.hinsight.ai.mcp.notion;

import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotionProperties props;
    private final NotionClient notionClient;
    private final BizUserDao bizUserDao;


    public Result sendDashboardImage(Long bizId, byte[] imageBytes) {
        if (!props.isConfigured()) {
            throw new IllegalStateException("노션 토큰(NOTION_TOKEN)이 설정되지 않았습니다.");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("전송할 이미지가 비어 있습니다.");
        }

        BizUser target = bizUserDao.findNotionTargetByBizId(bizId);
        if (target == null || target.getNotionPageId() == null || target.getNotionPageId().isBlank()) {
            throw new IllegalStateException("이 계정에 연결된 노션 페이지(notion_page_id)가 없습니다.");
        }

        String stamp = LocalDateTime.now().format(STAMP);
        String company = target.getCompanyName() != null ? target.getCompanyName() : "기업";
        String heading = String.format("%s 대시보드 · %s", company, stamp);
        String filename = String.format("dashboard-%s.png", stamp.replaceAll("[^0-9]", ""));

        notionClient.attachImageToPage(target.getNotionPageId(), heading, imageBytes, filename, "image/png");

        return new Result(company, target.getNotionPageId(), target.getNotionEmail());
    }

    public record Result(String companyName, String notionPageId, String notionEmail) {
    }
}
