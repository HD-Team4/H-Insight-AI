package com.hinsight.security.userdetails;

import com.hinsight.user.dao.UserDao;
import com.hinsight.user.model.vo.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 고객 로그인 시 Spring Security 가 호출하는 조회 서비스.
 * 오직 users 테이블만 조회한다 → 여기서 찾힌 계정은 곧 일반고객.
 */
@Service
@RequiredArgsConstructor
public class CustomerUserDetailsService implements UserDetailsService {

    private final UserDao userDao;

    @Override
    public CustomerUserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        User user = userDao.findByLoginId(loginId);
        if (user == null) {
            throw new UsernameNotFoundException("존재하지 않는 아이디입니다: " + loginId);
        }
        return new CustomerUserDetails(user);
    }
}
