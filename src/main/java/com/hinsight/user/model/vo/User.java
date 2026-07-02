package com.hinsight.user.model.vo;

import com.hinsight.common.vo.BaseTimeVo;
import lombok.Data;

import java.time.LocalDate;

/**
 * users 테이블 매핑 VO (일반고객).
 * DB 컬럼과 1:1 대응하며, 로그인/회원가입에서 DB <-> 애플리케이션 사이를 오가는 엔티티.
 * (role 컬럼은 없음 — 역할은 조회한 테이블에 따라 코드에서 부여한다. CustomerUserDetails 참고)
 */
@Data
public class User extends BaseTimeVo {

    private Long userId;       // user_id (PK)
    private String userName;   // user_name (NOT NULL, 표시 이름)
    private String loginId;    // login_id (UNIQUE)
    private String password;   // password (BCrypt 해시 저장)
    private String gender;     // gender char(1): 'M' / 'F' / null
    private LocalDate birthDate; // birth_date
    private String ageGroup;   // age_group
    private String email;      // email
    private String enabled;    // enabled char(1): 'Y' / 'N' (기본 'Y')
}
