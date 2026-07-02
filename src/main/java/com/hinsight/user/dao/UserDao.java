package com.hinsight.user.dao;

import com.hinsight.user.model.vo.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserDao {

    // 로그인 아이디로 조회 (없으면 null) - 로그인 인증에 사용
    User findByLoginId(String loginId);

    // 아이디 중복 여부 - 회원가입 검증에 사용
    boolean existsByLoginId(String loginId);

    // 신규 회원 저장 (userId 자동 채번)
    void insert(User user);
}
