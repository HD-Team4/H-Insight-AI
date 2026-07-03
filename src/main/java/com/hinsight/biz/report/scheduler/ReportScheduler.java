package com.hinsight.biz.report.scheduler;

import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
import com.hinsight.biz.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// 노션 페이지가 연결된 모든 기업에게 주간 리포트를 자동 발송 (기본 매주 월 08:00).
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "report.schedule", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportScheduler {

    private final BizUserDao bizUserDao;
    private final ReportService reportService;

    @Scheduled(cron = "${report.schedule.cron:0 0 8 * * MON}", zone = "${report.schedule.zone:Asia/Seoul}")
    public void sendWeeklyReports() {
        List<BizUser> targets = bizUserDao.findAllNotionTargets();
        log.info("[리포트 스케줄] 주간 리포트 발송 시작 — 대상 {}개 기업", targets.size());

        int ok = 0;
        for (BizUser user : targets) {
            try {
                reportService.sendWeeklyReport(user);
                ok++;
            } catch (Exception e) {
                log.error("[리포트 스케줄] 발송 실패 company={}", user.getCompanyName(), e);
            }
        }
        log.info("[리포트 스케줄] 완료 — {}/{} 성공", ok, targets.size());
    }
}
