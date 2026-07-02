package com.hinsight.security.userdetails;

import com.hinsight.common.constant.Role;
import com.hinsight.user.model.vo.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * users 테이블 계정을 Spring Security 인증 주체(principal)로 감싸는 어댑터.
 * users 에서 조회된 계정은 언제나 일반고객이므로 ROLE_CUSTOMER 를 코드에서 부여한다.
 */
public class CustomerUserDetails implements UserDetails {

    private final User user;

    public CustomerUserDetails(User user) {
        this.user = user;
    }

    /** 로그인 성공 후 세션에 담아 다른 기능(장바구니/주문 등)이 사용할 실제 식별자 */
    public Long getUserId() {
        return user.getUserId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 테이블 = 역할 이므로 상수 부여. (컬럼에서 읽지 않음)
        return List.of(new SimpleGrantedAuthority("ROLE_" + Role.CUSTOMER.name()));
    }

    @Override
    public String getPassword() {
        return user.getPassword(); // 저장된 BCrypt 해시
    }

    @Override
    public String getUsername() {
        return user.getLoginId();
    }

    @Override
    public boolean isEnabled() {
        return "Y".equals(user.getEnabled());
    }

    // isAccountNonExpired / isAccountNonLocked / isCredentialsNonExpired
    // 는 UserDetails 기본 구현(true) 사용
}
