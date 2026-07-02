package com.hinsight.biz.auth.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

/**
 * biz_users 테이블 매핑 VO (기업 회원).
 * 회원가입은 지원하지 않고 로그인만 사용한다. (role 컬럼 없음 — BizUserDetails 가 ROLE_BIZ 부여)
 */
@Data
public class BizUser extends BaseTimeVo {

    private Long bizId;          // biz_id (PK)
    private String loginId;      // login_id (UNIQUE)
    private String password;     // password (BCrypt 해시)
    private String companyName;  // company_name
    private String managerName;  // manager_name
    private String contactEmails; // contact_emails (json, 문자열 매핑 — 로그인엔 미사용)
    private String isActive;     // is_active char(1): 'Y' / 'N'
}
