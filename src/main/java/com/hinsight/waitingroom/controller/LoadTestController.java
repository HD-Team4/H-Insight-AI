package com.hinsight.waitingroom.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 가상 대기열 부하 테스트 전용 경량 진입점.
 *
 * <p>{@code /customer/**} 하위라 대기열 게이트(WaitingRoomInterceptor)가 그대로 적용된다.
 * 무거운 실제 상품 페이지(Elasticsearch+DB 다중 조회)를 부하 대상으로 삼으면 앱이 병목이라
 * 타임아웃만 난다 → 대기열 메커니즘만 순수하게 보려고 이 가벼운 엔드포인트를 둔다.
 *
 * <p>"처리(=커넥션 N초 점유)"는 인터셉터의 {@code holdConnectionForTest} 가 담당하고,
 * 여기 컨트롤러 자체는 즉시 {@code ok} 만 반환한다(운영에선 test-hold-ms=0 → 순수 핑).
 */
@Tag(name = "waiting-room-loadtest", description = "가상 대기열 부하 테스트용 경량 엔드포인트")
@RestController
@RequestMapping("/customer/loadtest")
public class LoadTestController {

    @Operation(summary = "부하 테스트 핑",
            description = "대기열 게이트 통과 후 즉시 ok. 처리 지연(1초)은 test-hold-ms 로 인터셉터가 재현한다")
    @GetMapping
    public String ping() {
        return "ok";
    }
}
