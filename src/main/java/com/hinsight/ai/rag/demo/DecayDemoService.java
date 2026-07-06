package com.hinsight.ai.rag.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>3. 검색 필터링(반감기 지수 감쇠) 시연용</b> 서비스. 실서비스 {@code ReviewSearchService} 의 재랭킹 수식과
 * {@code RagService} 의 실제 기본값(반감기 90일, wSim 0.6, wRec 0.4)을 그대로 사용한다.
 *
 * <p>감쇠 곡선은 순수 수식이라 벡터DB 없이 계산되며, 재랭킹 비교는 고정 샘플 리뷰(유사도·경과일)를 넣어
 * "최신순 / 유사도순 / 하이브리드"가 어떻게 달라지는지 보여준다.</p>
 */
@Service
public class DecayDemoService {

    private static final int MAX_DAYS = 400;
    private static final int CURVE_STEP = 2;

    /** 재랭킹 시연용 고정 샘플: (유사도, 경과일)을 다양화해 정렬 방식별 차이를 드러낸다. */
    private static final List<Sample> SAMPLES = List.of(
            new Sample(1, "어제 짧은 후기 — \"괜찮네요\"", 0.55, 1),
            new Sample(2, "며칠 써보니 만족스러워요", 0.70, 3),
            new Sample(3, "지난주 구매 후기 (배터리 언급)", 0.66, 9),
            new Sample(4, "한 달 전 — 색상 실물 후기", 0.62, 32),
            new Sample(5, "3개월 전 — 색상 자세히 설명", 0.88, 95),
            new Sample(6, "반년 전 — 사이즈/핏 상세", 0.83, 190),
            new Sample(7, "1년 전 — 가장 자세한 장문 후기", 0.93, 380)
    );

    @Value("${rag.half-life-days:90}") private double defHalfLife;
    @Value("${rag.w-sim:0.6}")         private double defWSim;
    @Value("${rag.w-rec:0.4}")         private double defWRec;

    /** 페이지 초기값(실제 운영 config 값). */
    public Defaults defaults() {
        return new Defaults(defHalfLife, defWSim, defWRec, MAX_DAYS);
    }

    /**
     * 감쇠 곡선 + 재랭킹을 계산한다.
     *
     * @param halfLifeOpt 반감기(일). null 이면 운영 기본값(90).
     * @param wSimOpt     유사도 가중(0~1). null 이면 0.6. wRec 은 1−wSim 로 둔다(운영도 0.6/0.4로 합=1).
     * @param cutoffOpt   리뉴얼 컷오프(일). null 이면 제외 없음(MAX_DAYS).
     */
    public DecayResponse compute(Double halfLifeOpt, Double wSimOpt, Integer cutoffOpt) {
        double h = clamp(halfLifeOpt == null ? defHalfLife : halfLifeOpt, 1, MAX_DAYS);
        double wSim = clamp(wSimOpt == null ? defWSim : wSimOpt, 0, 1);
        double wRec = 1.0 - wSim;
        int cutoff = (int) clamp(cutoffOpt == null ? MAX_DAYS : cutoffOpt, 1, MAX_DAYS);

        // 감쇠 곡선 w(t) = 0.5^(t/h)
        List<DecayResponse.Point> curve = new ArrayList<>();
        for (int day = 0; day <= MAX_DAYS; day += CURVE_STEP) {
            curve.add(new DecayResponse.Point(day, weight(day, h)));
        }

        // 반감기 배수 마커 (h, 2h, 3h)
        List<DecayResponse.Marker> markers = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            int day = (int) Math.round(h * k);
            if (day <= MAX_DAYS) {
                markers.add(new DecayResponse.Marker(day, Math.pow(0.5, k), "반감기 ×" + k + " (" + day + "일)"));
            }
        }

        // 샘플 리뷰 재랭킹
        List<DecayResponse.ReviewRow> reviews = new ArrayList<>();
        for (Sample s : SAMPLES) {
            double recency = weight(s.ageDays(), h);
            boolean excluded = s.ageDays() > cutoff;
            double finalScore = wSim * s.sim() + wRec * recency;
            reviews.add(new DecayResponse.ReviewRow(
                    s.id(), s.text(), s.sim(), s.ageDays(), recency, finalScore, excluded));
        }

        return new DecayResponse(h, wSim, wRec, cutoff, MAX_DAYS, curve, markers, reviews);
    }

    /** w(t) = 0.5^(t/h) = 2^(−t/h). */
    private double weight(double days, double halfLife) {
        return Math.pow(0.5, days / halfLife);
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private record Sample(long id, String text, double sim, int ageDays) {
    }

    /** @param halfLifeDays 반감기 @param wSim 유사도가중 @param wRec 최신성가중 @param maxDays 축 최대 */
    public record Defaults(double halfLifeDays, double wSim, double wRec, int maxDays) {
    }
}
