package com.hinsight.ai.rag.guard.demo;

import java.util.List;

/**
 * 금칙어 스캔 시연 응답. 시각화 페이지가 매칭 위치를 하이라이트하고 소요 시간을 표시하는 데 필요한 정보를 담는다.
 *
 * @param raw          원본 입력
 * @param normalized   정규화된 입력(공백·문장부호 제거, 소문자) — match 인덱스의 기준 문자열
 * @param blocked      금칙어가 하나라도 걸렸는지(=DROP 대상)
 * @param decision     판정 라벨(DROP_ABUSE / PASS)
 * @param matches      매칭된 금칙어와 정규화 문자열 내 위치
 * @param elapsedMicros Aho-Corasick 단일 스캔 소요 시간(마이크로초)
 * @param scannedChars 스캔한 정규화 문자 수(N)
 * @param dictionarySize 사전에 올라간 금칙어 개수(M)
 */
public record GuardScanResponse(
        String raw,
        String normalized,
        boolean blocked,
        String decision,
        List<Match> matches,
        double elapsedMicros,
        int scannedChars,
        int dictionarySize
) {
    /**
     * @param keyword 매칭된 금칙어
     * @param start   정규화 문자열 내 시작 인덱스(포함)
     * @param end     정규화 문자열 내 끝 인덱스(제외)
     */
    public record Match(String keyword, int start, int end) {
    }
}
