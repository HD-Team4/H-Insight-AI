package com.hinsight.biz.report.notify;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;

// 리포트 메일 발송. spring.mail.* + report.mail.from 설정 시에만 동작(없으면 skip).
@Slf4j
@Component
public class MailNotifier {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String from;

    public MailNotifier(ObjectProvider<JavaMailSender> mailSenderProvider,
                        @Value("${report.mail.from:}") String from) {
        this.mailSenderProvider = mailSenderProvider;
        this.from = from;
    }

    public boolean isConfigured() {
        return mailSenderProvider.getIfAvailable() != null && from != null && !from.isBlank();
    }

    public boolean send(List<String> to, String subject, String htmlBody) {
        return send(to, subject, htmlBody, null, null);
    }

    public boolean send(List<String> to, String subject, String htmlBody, byte[] image, String imageName) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null || from == null || from.isBlank()) {
            log.debug("메일 미설정(JavaMailSender/from 없음) — 발송 skip");
            return false;
        }
        if (to == null || to.isEmpty()) {
            log.debug("메일 수신자 없음 — skip");
            return false;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to.toArray(new String[0]));
            helper.setSubject(subject);

            boolean hasImage = image != null && image.length > 0;

            // 사진을 본문 맨 위에 배치(노션과 동일). 본문은 그 아래로.
            StringBuilder html = new StringBuilder();
            if (hasImage) {
                html.append("<div style=\"max-width:720px;margin:0 auto 16px\">")
                        .append("<img src=\"cid:dashboard\" style=\"width:100%;border:1px solid #eee;border-radius:10px\"/>")
                        .append("</div>");
            }
            html.append(htmlBody == null ? "" : htmlBody);

            // setText 를 addInline 보다 먼저 호출해야 본문이 정상 렌더링됨
            helper.setText(html.toString(), true);
            if (hasImage) {
                helper.addInline("dashboard", new ByteArrayResource(image), "image/png");
            }

            sender.send(msg);
            return true;
        } catch (Exception e) {
            log.warn("메일 발송 실패: {}", e.getMessage());
            return false;
        }
    }
}
