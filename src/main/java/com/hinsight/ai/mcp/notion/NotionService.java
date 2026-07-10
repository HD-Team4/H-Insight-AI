package com.hinsight.ai.mcp.notion;

import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
import com.hinsight.exception.ErrorCode;
import com.hinsight.exception.custom.notion.NotionIntegrationException;
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
            throw new NotionIntegrationException(ErrorCode.NOTION_NOT_CONFIGURED);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new NotionIntegrationException(ErrorCode.NOTION_IMAGE_REQUIRED);
        }

        BizUser target = bizUserDao.findNotionTargetByBizId(bizId);
        if (target == null || target.getNotionPageId() == null || target.getNotionPageId().isBlank()) {
            throw new NotionIntegrationException(ErrorCode.NOTION_PAGE_NOT_LINKED);
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
