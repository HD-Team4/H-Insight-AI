
ALTER TABLE biz_users
    ADD COLUMN notion_email   VARCHAR(255) NULL COMMENT '노션 공유 대상 이메일' AFTER contact_emails,
    ADD COLUMN notion_page_id VARCHAR(64)  NULL COMMENT '전송 대상 노션 페이지 ID' AFTER notion_email;
