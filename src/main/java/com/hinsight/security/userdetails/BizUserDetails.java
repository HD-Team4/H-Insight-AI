package com.hinsight.security.userdetails;

import com.hinsight.biz.auth.model.vo.BizUser;
import com.hinsight.common.constant.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * biz_users 계정을 Spring Security principal 로 감싸는 어댑터.
 * biz_users 에서 조회된 계정은 언제나 기업 회원이므로 ROLE_BIZ 를 코드에서 부여한다.
 */
public class BizUserDetails implements UserDetails {

    private final BizUser bizUser;

    public BizUserDetails(BizUser bizUser) {
        this.bizUser = bizUser;
    }

    /** 로그인 성공 후 세션에 담아 biz 기능이 사용할 식별자 */
    public Long getBizId() {
        return bizUser.getBizId();
    }

    public String getCompanyName() {
        return bizUser.getCompanyName();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + Role.BIZ.name()));
    }

    @Override
    public String getPassword() {
        return bizUser.getPassword();
    }

    @Override
    public String getUsername() {
        return bizUser.getLoginId();
    }

    @Override
    public boolean isEnabled() {
        return "Y".equals(bizUser.getIsActive()); // 고객은 enabled, 기업은 is_active 컬럼
    }
}
