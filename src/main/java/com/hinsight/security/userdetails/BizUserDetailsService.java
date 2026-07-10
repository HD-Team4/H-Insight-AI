package com.hinsight.security.userdetails;

import com.hinsight.biz.auth.dao.BizUserDao;
import com.hinsight.biz.auth.model.vo.BizUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 기업 로그인 시 Spring Security 가 호출하는 조회 서비스.
 * 오직 biz_users 테이블만 조회한다 → 여기서 찾힌 계정은 곧 기업 회원.
 */
@Service
@RequiredArgsConstructor
public class BizUserDetailsService implements UserDetailsService {

    private final BizUserDao bizUserDao;

    @Override
    public BizUserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        BizUser bizUser = bizUserDao.findByLoginId(loginId);
        if (bizUser == null) {
            throw new UsernameNotFoundException("존재하지 않는 기업 아이디입니다: " + loginId);
        }
        return new BizUserDetails(bizUser);
    }
}
