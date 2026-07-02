package com.hinsight.user.service;

import com.hinsight.exception.custom.user.DuplicateLoginIdException;
import com.hinsight.user.dao.UserDao;
import com.hinsight.user.model.dto.SignupRequest;
import com.hinsight.user.model.vo.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder; // PasswordEncoderConfig 의 BCrypt 빈

    /**
     * 회원가입: 아이디 중복 검사 → 비밀번호 BCrypt 인코딩 → 저장.
     * @return 생성된 userId
     */
    @Transactional
    public Long register(SignupRequest request) {
        if (userDao.existsByLoginId(request.loginId())) {
            throw new DuplicateLoginIdException();
        }

        User user = new User();
        user.setUserName(request.userName());   // NOT NULL — @NotBlank 로 이미 검증됨
        user.setLoginId(request.loginId());
        user.setPassword(passwordEncoder.encode(request.password())); // 평문 저장 금지
        user.setGender(blankToNull(request.gender()));
        user.setBirthDate(request.birthDate());
        user.setAgeGroup(blankToNull(request.ageGroup()));
        user.setEmail(blankToNull(request.email()));

        userDao.insert(user);
        return user.getUserId();
    }

    // 선택 입력값이 빈 문자열이면 null 로 정규화 (char(1) 등에 "" 저장 방지)
    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }
}
