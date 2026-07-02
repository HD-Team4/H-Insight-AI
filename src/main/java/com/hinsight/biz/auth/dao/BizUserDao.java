package com.hinsight.biz.auth.dao;

import com.hinsight.biz.auth.model.vo.BizUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizUserDao {

    // 로그인 아이디로 조회 (없으면 null) - 기업 로그인 인증에 사용. (회원가입 없음)
    BizUser findByLoginId(String loginId);
}
