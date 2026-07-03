package com.hinsight.user.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 마이페이지 비밀번호 변경 요청 DTO.
 * 현재 비밀번호 확인 + 새 비밀번호(+확인) 입력. 신·구 일치 여부는 컨트롤러에서 교차 검증한다.
 */
public record PasswordChangeRequest(

        @NotBlank(message = "현재 비밀번호를 입력해주세요.")
        String currentPassword,

        @NotBlank(message = "새 비밀번호를 입력해주세요.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상으로 입력해주세요.")
        String newPassword,

        @NotBlank(message = "새 비밀번호 확인을 입력해주세요.")
        String confirmPassword
) {
}
