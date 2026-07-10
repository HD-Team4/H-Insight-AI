package com.hinsight.ai.rag.demo;

import com.hinsight.ai.embedding.EmbeddingService;
import com.hinsight.ai.embedding.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * <b>2차 방어(시맨틱 군집화) 시연용</b> 서비스. 실서비스 라이브봇 파이프라인과 분리된 데모 전용이다.
 *
 * <p>흐름: 질문 N개를 실제 BGE-M3({@link EmbeddingService})로 임베딩 → 쌍별 코사인 유사도 →
 * 임계치 이상이면 한 군집으로 묶음(라이브봇 {@code SemanticQuestionAggregator} 와 동일한 그리디 방식) →
 * 1024차원 벡터를 고전 MDS(PCA와 동치)로 2D 투영. 대표질문(군집 시드) 1개만 LLM으로 가므로,
 * 질문 N개가 군집 K개로 줄어 <b>LLM 호출이 N→K</b> 로 감소한다.</p>
 *
 * <p>투영은 임베딩을 L2 정규화한 뒤 수행한다. 정규화 공간에서 ‖a−b‖²=2−2·cos 이므로 유클리드 거리가
 * 코사인과 단조 대응하고, 코사인 임계치는 √(2−2·t) 라는 고정 반경으로 그릴 수 있다.</p>
 */
@Service
public class SemanticDemoService {

    private static final Logger log = LoggerFactory.getLogger(SemanticDemoService.class);

    /**
     * 스크립트 [2-②] 기본 예시. BGE-M3 실측 코사인으로 <b>확실히 뭉치는</b> 두 군집(색상·배송) + 단발 질문 1건.
     * (색상 seed 기준: 색이 어두운 편인가요 0.96, 실물 색 어둡나요 0.85 / 배송 seed 기준: 언제쯤 배송되나요 0.97,
     *  군집 간은 0.75 미만이라 안 섞임 — 문자가 달라도 의미가 같으면 묶인다는 걸 보여줌)
     */
    public static final List<String> DEFAULT_QUESTIONS = List.of(
            "색상 어두운가요", "색이 어두운 편인가요", "실물 색 어둡나요",
            "배송 언제 와요?", "언제쯤 배송되나요?", "재질이 뭔가요");

    private static final int MAX_QUESTIONS = 12;
    private static final double DEFAULT_THRESHOLD = 0.75;   // 스크립트 기준(운영 라이브봇은 0.85로 보수적)
    private static final int DEFAULT_REPEAT_THRESHOLD = 2;  // 몇 번 반복돼야 트리거(운영 rag.bot.threshold 기본 3)

    private final EmbeddingService embeddingService;
    /** 데모 반복 실행 시 동일 문장을 매번 임베딩하지 않도록 캐시(모델 호출 절약). */
    private final ConcurrentMap<String, float[]> embedCache = new ConcurrentHashMap<>();

    public SemanticDemoService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<String> defaultQuestions() {
        return DEFAULT_QUESTIONS;
    }

    /**
     * 질문 목록을 임베딩·군집화·2D 투영해 시각화에 필요한 모든 값을 반환한다.
     *
     * @param rawQuestions    사용자 입력(빈 값이면 기본 예시). 각 줄 = 들어온 채팅 1건.
     * @param thresholdOpt    코사인 임계치(null 이면 0.75)
     * @param repeatThreshOpt 트리거 반복 횟수(null 이면 2)
     */
    public SemanticSpaceResponse analyze(List<String> rawQuestions, Double thresholdOpt, Integer repeatThreshOpt) {
        List<String> questions = clean(rawQuestions);
        double threshold = clamp(thresholdOpt == null ? DEFAULT_THRESHOLD : thresholdOpt, 0.1, 0.99);
        int repeatThreshold = (int) clamp(repeatThreshOpt == null ? DEFAULT_REPEAT_THRESHOLD : repeatThreshOpt, 1, 10);
        int n = questions.size();

        // 1) 임베딩(실측) + L2 정규화
        float[][] vecs = new float[n][];
        float[][] unit = new float[n][];
        for (int i = 0; i < n; i++) {
            vecs[i] = embedCached(questions.get(i));
            unit[i] = l2normalize(vecs[i]);
        }
        int dims = n > 0 ? vecs[0].length : 0;

        // 2) 쌍별 코사인 유사도 행렬
        double[][] sim = new double[n][n];
        for (int i = 0; i < n; i++) {
            sim[i][i] = 1.0;
            for (int j = i + 1; j < n; j++) {
                double s = Vectors.cosine(vecs[i], vecs[j]);
                sim[i][j] = s;
                sim[j][i] = s;
            }
        }

        // 3) 그리디 군집화 (시드=대표, cos(질문, 시드) ≥ threshold 면 흡수) — 집계기와 동일
        int[] clusterOf = new int[n];
        List<Integer> seeds = new ArrayList<>();
        List<List<Integer>> members = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int best = -1;
            double bestSim = -1.0;
            for (int c = 0; c < seeds.size(); c++) {
                double s = sim[i][seeds.get(c)];
                if (s > bestSim) { bestSim = s; best = c; }
            }
            if (best >= 0 && bestSim >= threshold) {
                members.get(best).add(i);
                clusterOf[i] = best;
            } else {
                seeds.add(i);
                List<Integer> m = new ArrayList<>();
                m.add(i);
                members.add(m);
                clusterOf[i] = seeds.size() - 1;
            }
        }

        // 4) 정규화 벡터를 2D로 투영 (고전 MDS = PCA 스코어)
        double[][] coords = project2d(unit);

        // 5) 응답 조립
        double radius = Math.sqrt(Math.max(0.0, 2.0 - 2.0 * threshold));

        List<SemanticSpaceResponse.Point> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new SemanticSpaceResponse.Point(
                    i, questions.get(i), coords[i][0], coords[i][1], clusterOf[i]));
        }

        List<SemanticSpaceResponse.Cluster> clusters = new ArrayList<>();
        int fired = 0;
        for (int c = 0; c < seeds.size(); c++) {
            int seed = seeds.get(c);
            int count = members.get(c).size();
            boolean triggered = count >= repeatThreshold;   // 반복 임계치 이상만 LLM 호출
            if (triggered) fired++;
            clusters.add(new SemanticSpaceResponse.Cluster(
                    c, seed, members.get(c), questions.get(seed),
                    count, triggered, coords[seed][0], coords[seed][1], radius));
        }

        // 실제 LLM 호출 수 = 트리거된 군집 수(대표질문 1개씩). 비교 기준은 "질문마다 답했을 때"(=N).
        double saved = n > 0 ? (double) (n - fired) / n : 0.0;
        log.info("[시맨틱-데모] 질문 {}건 → 군집 {}개, 트리거 {}개 (cos≥{}, 반복≥{}), LLM 호출 {}→{}",
                n, seeds.size(), fired, threshold, repeatThreshold, n, fired);

        return new SemanticSpaceResponse(dims, threshold, repeatThreshold, radius,
                points, clusters, sim, n, fired, saved);
    }

    // ----------------- 임베딩 -----------------

    private float[] embedCached(String text) {
        return embedCache.computeIfAbsent(text, embeddingService::embed);
    }

    private float[] l2normalize(float[] v) {
        double norm = 1e-9;
        for (float f : v) norm += (double) f * f;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    // ----------------- 2D 투영 (고전 MDS) -----------------

    /**
     * 정규화 벡터 n개를 2D로 투영한다. 중심화한 뒤 n×n 그램행렬의 상위 2개 고유쌍을
     * 거듭제곱법(power iteration)으로 구해 좌표 = 고유벡터·√고유값 으로 놓는다(= PCA 스코어).
     */
    private double[][] project2d(float[][] unit) {
        int n = unit.length;
        double[][] coords = new double[n][2];
        if (n == 0) return coords;
        if (n == 1) return coords;   // 한 점은 원점
        int d = unit[0].length;

        // 중심화
        double[] mean = new double[d];
        for (float[] u : unit) for (int k = 0; k < d; k++) mean[k] += u[k];
        for (int k = 0; k < d; k++) mean[k] /= n;
        double[][] c = new double[n][d];
        for (int i = 0; i < n; i++) for (int k = 0; k < d; k++) c[i][k] = unit[i][k] - mean[k];

        // 그램행렬 G[i][j] = c_i · c_j
        double[][] g = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                double dot = 0;
                for (int k = 0; k < d; k++) dot += c[i][k] * c[j][k];
                g[i][j] = dot;
                g[j][i] = dot;
            }
        }

        double[] e1 = new double[n];
        double l1 = topEigen(g, e1);
        deflate(g, e1, l1);
        double[] e2 = new double[n];
        double l2 = topEigen(g, e2);

        double s1 = Math.sqrt(Math.max(l1, 0));
        double s2 = Math.sqrt(Math.max(l2, 0));
        for (int i = 0; i < n; i++) {
            coords[i][0] = e1[i] * s1;
            coords[i][1] = e2[i] * s2;
        }
        return coords;
    }

    /** 대칭 PSD 행렬의 최대 고유값/고유벡터를 거듭제곱법으로 구한다(고유벡터는 outVec 에 단위벡터로 채움). */
    private double topEigen(double[][] m, double[] outVec) {
        int n = m.length;
        double[] v = new double[n];
        Random r = new Random(42);                 // 결정적 레이아웃
        for (int i = 0; i < n; i++) v[i] = r.nextDouble() - 0.5;
        normalizeInPlace(v);

        for (int it = 0; it < 300; it++) {
            double[] w = matVec(m, v);
            double norm = norm(w);
            if (norm < 1e-12) break;
            for (int i = 0; i < n; i++) w[i] /= norm;
            v = w;
        }
        System.arraycopy(v, 0, outVec, 0, n);
        return rayleigh(m, v);                      // λ ≈ vᵀ M v
    }

    private void deflate(double[][] m, double[] vec, double lambda) {
        int n = m.length;
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                m[i][j] -= lambda * vec[i] * vec[j];
    }

    private double[] matVec(double[][] m, double[] v) {
        int n = m.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += m[i][j] * v[j];
            out[i] = s;
        }
        return out;
    }

    private double rayleigh(double[][] m, double[] v) {
        double[] mv = matVec(m, v);
        double num = 0, den = 0;
        for (int i = 0; i < v.length; i++) { num += v[i] * mv[i]; den += v[i] * v[i]; }
        return den < 1e-12 ? 0 : num / den;
    }

    private double norm(double[] v) {
        double s = 0;
        for (double x : v) s += x * x;
        return Math.sqrt(s);
    }

    private void normalizeInPlace(double[] v) {
        double n = norm(v);
        if (n < 1e-12) return;
        for (int i = 0; i < v.length; i++) v[i] /= n;
    }

    // ----------------- 입력 정리 -----------------

    private List<String> clean(List<String> in) {
        if (in == null || in.isEmpty()) return new ArrayList<>(DEFAULT_QUESTIONS);
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s == null) continue;
            String t = s.strip();
            if (t.isEmpty()) continue;
            out.add(t);
            if (out.size() >= MAX_QUESTIONS) break;
        }
        return out.isEmpty() ? new ArrayList<>(DEFAULT_QUESTIONS) : out;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
