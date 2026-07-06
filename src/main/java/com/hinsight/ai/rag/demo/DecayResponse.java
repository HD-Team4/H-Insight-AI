package com.hinsight.ai.rag.demo;

import java.util.List;

/**
 * 3. 검색 필터링(반감기 지수 감쇠) 시연 응답.
 *
 * <p>실서비스 {@code ReviewSearchService} 의 재랭킹 수식을 그대로 재현한다:
 * {@code recency = 0.5^(Δt/h) = 2^(-Δt/h)}, {@code finalScore = wSim·sim + wRec·recency}.
 * 리뉴얼 컷오프보다 오래된 리뷰는 검색 대상에서 제외({@code written_at ≥ cutoff}).</p>
 *
 * @param halfLifeDays 반감기 h(일)
 * @param wSim         의미 유사도 가중
 * @param wRec         최신성 가중(= 1 − wSim)
 * @param cutoffDays   리뉴얼 컷오프(일). 이보다 오래된 리뷰는 제외.
 * @param maxDays      곡선/축 최대 일수
 * @param curve        감쇠 곡선 점들
 * @param markers      반감기 배수 지점(h→0.5, 2h→0.25, 3h→0.125)
 * @param reviews      샘플 리뷰의 재랭킹 결과
 */
public record DecayResponse(
        double halfLifeDays,
        double wSim,
        double wRec,
        int cutoffDays,
        int maxDays,
        List<Point> curve,
        List<Marker> markers,
        List<ReviewRow> reviews
) {
    /** @param day 경과일 Δt @param weight 가중치 w(t) */
    public record Point(int day, double weight) {
    }

    /** @param day 지점 @param weight 그 지점의 가중치 @param label 표시 라벨 */
    public record Marker(int day, double weight, String label) {
    }

    /**
     * @param id         리뷰 id
     * @param text       리뷰 요약 텍스트
     * @param sim        의미 유사도(0~1)
     * @param ageDays    경과일 Δt
     * @param recency    시간 가중치 0.5^(Δt/h)
     * @param finalScore wSim·sim + wRec·recency
     * @param excluded   컷오프로 제외됐는지(재랭킹 대상 아님)
     */
    public record ReviewRow(long id, String text, double sim, int ageDays,
                            double recency, double finalScore, boolean excluded) {
    }
}
