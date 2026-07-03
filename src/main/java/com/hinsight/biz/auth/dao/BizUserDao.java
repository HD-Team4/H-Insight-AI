package com.hinsight.biz.auth.dao;

import com.hinsight.biz.auth.model.vo.BizUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BizUserDao {

    // 로그인 아이디로 조회 (없으면 null) - 기업 로그인 인증에 사용. (회원가입 없음)
    BizUser findByLoginId(String loginId);

    // biz_id 로 노션 연동 정보(notion_email, notion_page_id)만 조회 - 대시보드 노션 전송에 사용.
    BizUser findNotionTargetByBizId(Long bizId);
}
