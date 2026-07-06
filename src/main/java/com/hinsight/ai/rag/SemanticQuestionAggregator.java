package com.hinsight.ai.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 라이브 세션별로 "의미가 비슷한 질문"을 하나의 군집으로 묶어 빈도를 집계한다.
 *
 * <p>문구가 달라도("색상 어두운가요" / "옷색 어떰")  임베딩 코사인 유사도가 임계 이상이면 같은 군집으로 카운트한다.
 * 단일 인스턴스(내장 SimpleBroker) 전제라 인메모리로 유지하며, 세션별 군집 리스트에 락을 걸어 동시성만 보호한다.
 * (다중 인스턴스로 확장 시 Redis 벡터검색 등으로 교체 필요.)</p>
 */
@Component
public class SemanticQuestionAggregator {

    private final Map<Long, List<Cluster>> bySession = new ConcurrentHashMap<>();

    /**
     * 질문 1건을 집계하고, 이번 집계로 트리거 조건이 충족됐는지 반환한다.
     *
     * @param vec          질문 임베딩(BGE-M3, 1024d)
     * @param threshold    트리거 최소 누적 횟수
     * @param simThreshold 같은 군집으로 볼 코사인 유사도 하한 (예: 0.75)
     * @param windowMs     군집 유지 창(ms). 이보다 오래 안 나온 군집은 소멸.
     * @param cooldownMs   같은 군집 재답변 방지 시간(ms).
     */
    public Result record(long sessionId, String question, float[] vec,
                         int threshold, double simThreshold, long windowMs, long cooldownMs) {
        long now = System.currentTimeMillis();
        List<Cluster> clusters = bySession.computeIfAbsent(sessionId, k -> new ArrayList<>());

        synchronized (clusters) {
            clusters.removeIf(c -> now - c.lastSeenMs > windowMs);   // 만료 군집 정리

            Cluster best = null;
            double bestSim = -1.0;
            for (Cluster c : clusters) {
                double s = cosine(vec, c.centroid);
                if (s > bestSim) { bestSim = s; best = c; }
            }

            Cluster target;
            double matchedSim;
            if (best != null && bestSim >= simThreshold) {   // 기존 군집에 흡수
                target = best;
                target.count++;
                matchedSim = bestSim;
            } else {                                          // 새 군집 생성
                target = new Cluster(vec, question, now);
                clusters.add(target);
                matchedSim = 1.0;
            }
            target.lastSeenMs = now;
            // 대표질문은 군집을 만든 '시드(중심)' 질문으로 고정한다.
            // 시드는 곧 centroid 이므로, 군집이 대표하는 의미와 가장 일치한다.

            boolean triggered = false;
            if (target.count >= threshold
                    && (target.answeredAtMs == 0 || now - target.answeredAtMs > cooldownMs)) {
                target.answeredAtMs = now;
                triggered = true;
            }
            return new Result(target.count, matchedSim, triggered, target.repQuestion);
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    /**
     * @param count                  트리거된(또는 매칭된) 군집의 누적 횟수
     * @param similarity             기존 군집과의 유사도(신규 군집이면 1.0)
     * @param triggered              이번 집계로 트리거 조건 충족 여부
     * @param representativeQuestion RAG 에 넘길 대표질문
     */
    public record Result(int count, double similarity, boolean triggered, String representativeQuestion) {
    }

    private static final class Cluster {
        final float[] centroid;   // 대표(최초) 임베딩
        String repQuestion;
        int count = 1;
        long lastSeenMs;
        long answeredAtMs = 0;    // 0 = 아직 미답변

        Cluster(float[] centroid, String question, long now) {
            this.centroid = centroid;
            this.repQuestion = question;
            this.lastSeenMs = now;
        }
    }
}
