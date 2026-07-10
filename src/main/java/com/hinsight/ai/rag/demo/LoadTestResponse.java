package com.hinsight.ai.rag.demo;

import java.util.List;

/**
 * 1만건 토큰 부하 벤치마크 결과.
 *
 * <p>가드 판정·임베딩·코사인 군집화·Redis 캐시는 <b>실서비스 빈을 그대로 태워 실측</b>하고,
 * Gemini 호출만 실제로 하지 않는다(비용·레이트리밋). 대신 "몇 번 호출로 줄었는가"를 실측하고
 * 토큰은 {@code approxTokens}(≈700/호출)로 환산한다.</p>
 *
 * @param count         생성·투입한 채팅 수
 * @param guardPassed   1차 가드 통과(질문) 수
 * @param guardDropped  1차 가드 파기(비질문·욕설) 수
 * @param distinctEmbeds 실제 BGE-M3 임베딩을 계산한 서로 다른 문장 수(반복은 캐시)
 * @param clusters      코사인 0.85 군집 수(의미상 서로 다른 질문 수)
 * @param naiveCalls    최적화 없음: 모든 채팅을 호출(=count)
 * @param clusterCalls  군집화만: 군집 트리거마다 호출(재답변 포함)
 * @param bothCalls     군집화+캐시: 실제 Gemini 호출(캐시 MISS)
 * @param cacheHits     캐시 HIT(재사용, 호출 0)
 * @param cacheHitRate  트리거 대비 HIT 비율(0~1)
 * @param perCallTokens 호출당 토큰 근사(컨텍스트+답변)
 * @param naiveTokens   최적화 없음 토큰
 * @param clusterTokens 군집화만 토큰
 * @param bothTokens    군집화+캐시 토큰
 * @param savingsPct    최종 토큰 절감률(%) vs naive
 * @param guardMs       가드 판정 전체 소요(ms)
 * @param embedMs       임베딩 전체 소요(ms)
 * @param totalMs       전체 파이프라인 소요(ms)
 * @param throughput    처리량(채팅/초)
 * @param windowSec     투입을 분산시킨 가상 방송 창(초)
 * @param products      동시에 방송한 상품 세션 수(상품별 캐시)
 * @param series        시간 경과별 누적 호출 곡선(naive/cluster/both)
 * @param topics        입력 질문 주제 분포
 * @param sensitivity   임계치별 군집수·호출수(임계치 선택이 임의가 아님을 보여줌)
 */
public record LoadTestResponse(
        int count,
        int guardPassed,
        int guardDropped,
        int distinctEmbeds,
        int clusters,
        long naiveCalls,
        long clusterCalls,
        long bothCalls,
        long cacheHits,
        double cacheHitRate,
        int perCallTokens,
        long naiveTokens,
        long clusterTokens,
        long bothTokens,
        double savingsPct,
        long guardMs,
        long embedMs,
        long totalMs,
        long throughput,
        int windowSec,
        int products,
        List<Point> series,
        List<Topic> topics,
        List<Sensitivity> sensitivity
) {
    /** 누적 곡선의 한 점. @param tSec 경과 초 @param naive 누적 naive 호출 @param cluster 누적 군집화 호출 @param both 누적 최종 호출 */
    public record Point(int tSec, long naive, long cluster, long both) {
    }

    /** 주제별 입력 분포. @param name 주제 @param chats 해당 주제 채팅 수 @param triggers 해당 주제 군집의 트리거 수 */
    public record Topic(String name, int chats, int triggers) {
    }

    /**
     * 임계치 민감도 한 점.
     * @param threshold 코사인 군집 임계치
     * @param clusters  그 임계치에서의 군집 수(=서로 다른 질문 의도 수)
     * @param calls     그 임계치에서의 실제 LLM 호출 수(캐시 반영)
     * @param used      실서비스 기본값(0.85) 여부
     */
    public record Sensitivity(double threshold, int clusters, long calls, boolean used) {
    }
}
