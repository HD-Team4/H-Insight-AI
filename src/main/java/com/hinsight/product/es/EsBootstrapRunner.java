package com.hinsight.product.es;

import com.hinsight.product.service.ProductIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 부팅 시 ES 인덱스/동의어 세트를 준비한다(없을 때만).
 * ES가 떠있지 않아도 앱 기동을 막지 않도록 best-effort 처리.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsBootstrapRunner implements ApplicationRunner {

    private final ProductIndexService productIndexService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean created = productIndexService.createIndexIfAbsent();
            if (created) {
                log.info("[ES] 부팅 초기화: 인덱스 신규 생성됨 (재색인은 POST /api/es/reindex 로 수행)");
            }
        } catch (Exception e) {
            log.warn("[ES] 부팅 초기화 실패(무시하고 계속): {}", e.getMessage());
        }
    }
}
