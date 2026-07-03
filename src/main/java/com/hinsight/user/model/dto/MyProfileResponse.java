package com.hinsight.user.model.dto;

import com.hinsight.user.model.vo.User;

import java.time.LocalDate;

/**
 * 마이페이지 상단 개인정보 요약 응답 DTO.
 * VO(User)에서 화면 표시에 필요한 값만 추려 담는다 (password 등 민감 필드 제외).
 */
public record MyProfileResponse(
        String userName,
        String loginId,
        String gender,
        LocalDate birthDate,
        String ageGroup,
        String email
) {

    public static MyProfileResponse of(User user) {
        return new MyProfileResponse(
                user.getUserName(),
                user.getLoginId(),
                user.getGender(),
                user.getBirthDate(),
                user.getAgeGroup(),
                user.getEmail()
        );
    }

    /** 성별 코드(M/F/null)를 화면 표기로 변환 */
    public String genderLabel() {
        if ("M".equals(gender)) return "남성";
        if ("F".equals(gender)) return "여성";
        return "미설정";
    }
}
