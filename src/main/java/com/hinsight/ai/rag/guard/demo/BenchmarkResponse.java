package com.hinsight.ai.rag.guard.demo;

import java.util.List;

/**
 * "도입 전 vs 도입 후" 벤치마크 응답. 동일한 입력 문장을 금칙어 개수(M)를 늘려가며
 * <b>단순 contains 반복(O(N·M))</b> 과 <b>Aho-Corasick 단일 스캔(O(N))</b> 으로 각각 측정한 결과다.
 *
 * @param sampleLength 스캔한 입력 길이(N, 정규화 후 문자 수)
 * @param iterations   측정 반복 횟수(중앙값 채택)
 * @param points       금칙어 개수별 측정점
 */
public record BenchmarkResponse(
        int sampleLength,
        int iterations,
        List<Point> points
) {
    /**
     * @param size        금칙어 개수(M)
     * @param naiveMicros 단순 contains 반복 소요(µs, 중앙값)
     * @param ahoMicros   Aho-Corasick 단일 스캔 소요(µs, 중앙값)
     * @param speedup     naive / aho (몇 배 빨라졌는가)
     */
    public record Point(int size, double naiveMicros, double ahoMicros, double speedup) {
    }
}
