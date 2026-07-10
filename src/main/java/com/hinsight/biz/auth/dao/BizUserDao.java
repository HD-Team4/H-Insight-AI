package com.hinsight.biz.auth.dao;

import com.hinsight.biz.auth.model.vo.BizUser;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BizUserDao {

    // 로그인 아이디로 조회 (없으면 null) - 기업 로그인 인증에 사용. (회원가입 없음)
    BizUser findByLoginId(String loginId);

    // biz_id 로 노션 연동 정보(notion_email/page_id + contact_emails)만 조회 - 노션 전송/리포트에 사용.
    BizUser findNotionTargetByBizId(Long bizId);

    // notion_page_id 가 설정된 모든 기업 - 주간 리포트 자동 발송 대상.
    List<BizUser> findAllNotionTargets();
}
