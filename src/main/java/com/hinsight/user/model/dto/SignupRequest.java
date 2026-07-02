package com.hinsight.user.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * 회원가입 요청 DTO. 폼 파라미터를 바인딩하고 Bean Validation으로 검증한다.
 * (VO(User)와 분리 — 클라이언트 입력 형태와 DB 엔티티를 섞지 않기 위함)
 */
public record SignupRequest(

        @NotBlank(message = "이름을 입력해주세요.")
        @Size(max = 20, message = "이름은 20자 이하로 입력해주세요.")
        String userName,

        @NotBlank(message = "아이디를 입력해주세요.")
        @Size(min = 4, max = 50, message = "아이디는 4~50자로 입력해주세요.")
        String loginId,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min = 8, max = 100, message = "비밀번호는 8자 이상으로 입력해주세요.")
        String password,

        String gender,

        @Past(message = "생년월일이 올바르지 않습니다.")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate birthDate,

        String ageGroup,

        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email
) {
}
