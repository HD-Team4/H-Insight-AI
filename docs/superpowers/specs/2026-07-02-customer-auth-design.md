# 고객(users) 회원가입 + 로그인 설계 (Spring Security formLogin)

- 날짜: 2026-07-02
- 브랜치: KAN-51-user
- 범위: `users` 테이블 기반 **일반고객** 회원가입/로그인만. biz(viz)·기타 기능은 미변경.

## 결정 사항

### 1. 역할(role) 저장 방식 — DB 컬럼/중계 테이블 없음
- 역할은 DB에 저장하지 않고, **로그인 시 어느 테이블에서 조회했는지에 따라 코드가 부여**한다.
- `CustomerUserDetails` → `ROLE_CUSTOMER`, (미구현) `BizUserDetails` → `ROLE_BIZ`.
- 이유: 역할은 고정 2개이고 각 테이블과 1:1(모든 `users` 행 = 고객). 컬럼은 "항상 같은 값"을 저장하는 중복이며 drift 위험만 있음. 중계 테이블은 다대다용이라 과함. DB 스키마 변경 불필요.

### 2. 인증 방식 — Spring Security formLogin + HttpSession
- `POST /login`(필터 처리) → `CustomerUserDetailsService.loadUserByUsername` → BCrypt 검증.
- 성공 시 `SecurityContext`(role)와 별도로 `LoginSuccessHandler`가 세션에 `userId`(Long) 저장.
  - 기존 `CartController`/`OrderController`가 이미 `session.getAttribute("userId")`를 읽으므로, 이 다리로 다른 기능이 코드 변경 없이 실제 로그인 유저를 사용.

### 3. 인가 정책 — A안(무중단)
- 공개 포함 전 경로 `permitAll` 유지(다른 기능 무중단). 라우트별 보호는 추후 한 줄로 강화.
- 이미 로그인한 유저는 `/login`·`/users/signup` 접근 시 `/`로 리다이렉트.
- `/biz/**` 체인은 미변경.

### 4. DTO/VO 분리
- VO `User`: DB 엔티티. DTO `SignupRequest`(record + Bean Validation). 로그인은 formLogin이 `username`/`password` 파라미터를 직접 받아 별도 DTO 없음.

## URL
- `GET /login` (뷰), `POST /login` (Security), `POST /logout` (Security)
- `GET /users/signup` (폼), `POST /users/signup` (가입 → `/login?signup`)

## 변경 파일
VO `user/model/vo/User`, DTO `user/model/dto/SignupRequest`(신규), DAO `user/dao/UserDao`,
Mapper `mapper/user/UserMapper.xml`, Service `user/service/UserService`,
Security `security/userdetails/CustomerUserDetails`·`CustomerUserDetailsService`·
`security/handler/LoginSuccessHandler`·`security/CustomerSecurityConfig`,
Controller `auth/controller/AuthController`·`user/controller/UserController`,
View `templates/customer/auth/login.html`·`signup.html`, `templates/fragments/header.html`(로그인/로그아웃 표시).

## 범위 밖 / 주의
- biz 로그인, `CustomAuthEntryPoint`/`CustomAccessDeniedHandler`/`LoginFailureHandler`/`AuthService`/`AuthInterceptor` 스텁 미변경.
- CSRF는 Spring Security 기본값(켜짐) 유지. login/signup/logout 폼은 `th:action`으로 토큰 자동 주입.
  cart/order의 fetch AJAX는 CSRF 토큰 미전송 → 별도 대응 필요(기존 이슈, 범위 밖).
