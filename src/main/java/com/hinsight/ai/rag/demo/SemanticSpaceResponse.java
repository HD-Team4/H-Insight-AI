package com.hinsight.ai.rag.demo;

import java.util.List;

/**
 * 2차 방어(시맨틱 군집화) 시연 응답.
 *
 * <p>질문들을 실제 BGE-M3(1024d)로 임베딩해 코사인 유사도를 구하고, 임계치 이상이면 한 군집으로 묶는다
 * (라이브봇 {@code SemanticQuestionAggregator} 와 동일한 그리디 군집화). 1024차원 벡터는
 * 고전 MDS(=PCA)로 2D에 투영해 "문자가 달라도 의미가 가까우면 공간상 가깝다"를 눈으로 보여준다.</p>
 *
 * @param dims             임베딩 차원(1024)
 * @param threshold        같은 군집으로 볼 코사인 유사도 하한
 * @param repeatThreshold  트리거에 필요한 최소 반복 횟수(이 횟수만큼 쌓인 군집만 LLM 호출)
 * @param thresholdRadius  임계치에 해당하는 정규화 공간 거리(√(2−2·threshold)) — 좌표와 같은 단위
 * @param points           2D 투영된 질문 점들
 * @param clusters         군집(대표질문 = 시드)
 * @param similarity       전체 쌍별 코사인 유사도 행렬(N×N)
 * @param llmCallsWithout  질문마다 답했을 때의 LLM 호출 수(=질문 수 N)
 * @param llmCallsWith     실제 호출 수(=반복 임계치를 넘겨 트리거된 군집 수)
 * @param savedRatio       절감 비율((N−호출수)/N)
 */
public record SemanticSpaceResponse(
        int dims,
        double threshold,
        int repeatThreshold,
        double thresholdRadius,
        List<Point> points,
        List<Cluster> clusters,
        double[][] similarity,
        int llmCallsWithout,
        int llmCallsWith,
        double savedRatio
) {
    /**
     * @param index     질문 인덱스
     * @param text      질문 원문
     * @param x         MDS 투영 x(거리 단위)
     * @param y         MDS 투영 y(거리 단위)
     * @param clusterId 소속 군집 id
     */
    public record Point(int index, String text, double x, double y, int clusterId) {
    }

    /**
     * @param id             군집 id
     * @param seedIndex      시드(대표) 질문 인덱스
     * @param members        소속 질문 인덱스 목록
     * @param representative 대표질문(시드 = centroid)
     * @param count          누적 반복 횟수(= members 크기)
     * @param triggered      반복 임계치 이상이라 LLM 을 태우는지 여부(false 면 대기/미답변)
     * @param cx             군집 중심 x(시드 좌표)
     * @param cy             군집 중심 y(시드 좌표)
     * @param radius         임계치 반경(= thresholdRadius, 좌표 단위)
     */
    public record Cluster(int id, int seedIndex, List<Integer> members, String representative,
                          int count, boolean triggered, double cx, double cy, double radius) {
    }
}
