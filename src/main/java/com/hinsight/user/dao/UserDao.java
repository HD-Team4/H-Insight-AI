package com.hinsight.user.dao;

import com.hinsight.user.model.vo.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserDao {

    // 로그인 아이디로 조회 (없으면 null) - 로그인 인증에 사용
    User findByLoginId(String loginId);

    // PK로 조회 (없으면 null) - 마이페이지 등 로그인 유저 본인 조회에 사용
    User findById(Long userId);

    // 아이디 중복 여부 - 회원가입 검증에 사용
    boolean existsByLoginId(String loginId);

    // 신규 회원 저장 (userId 자동 채번)
    void insert(User user);

    // 비밀번호 변경 (마이페이지) - password 는 BCrypt 해시로 전달받는다
    void updatePassword(@Param("userId") Long userId, @Param("password") String password);
}
