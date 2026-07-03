package com.hinsight.chatbot.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hinsight.chatbot.model.dto.ParsedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Gemini 로 사용자 질문 → 구조화 조건(ParsedQuery) 추출.
 * 실패 시(파싱 오류 등) 필터 없이 원문을 의미검색으로 쓰도록 안전하게 폴백한다.
 */
@Service
public class QueryConditionExtractor {

    private static final Logger log = LoggerFactory.getLogger(QueryConditionExtractor.class);

    private final ChatClient chatClient;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public QueryConditionExtractor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    private static final String SYSTEM = """
            너는 의류 쇼핑몰 검색 질의를 구조화하는 도우미다.
            사용자 문장에서 조건을 추출해 JSON 으로만 답하라. 설명, 코드블록(```), 그 외 텍스트 금지.

            필드:
            - gender: "남성" 또는 "여성" 중 하나. 알 수 없으면 null.
            - subcat: 다음 중 정확히 하나 또는 null.
              [팬츠, 티셔츠, 니트, 아우터, 패션잡화, 스커트, 셔츠/블라우스, 원피스, 재킷/베스트, 셔츠, 쥬얼리/시계, 재킷, 라운지/언더웨어]
            - minPrice: 최소 가격(원, 정수) 또는 null.
            - maxPrice: 최대 가격(원, 정수) 또는 null.
            - semanticText: 색상/소재/느낌/용도 등 의미검색에 쓸 한국어 구절 (가격·성별 표현은 제외).

            규칙:
            - "N만원대"  => minPrice=N0000, maxPrice=(N+1)만원-1  (예: 5만원대 => minPrice 50000, maxPrice 59999)
            - "N만원 이하/미만" => maxPrice 만 설정, "N만원 이상" => minPrice 만 설정.
            - 원피스/스커트/블라우스는 여성으로 간주. 확실치 않으면 gender=null.
            - subcat 은 반드시 위 목록의 값과 정확히 일치해야 하며, 애매하면 null.

            예) 입력: "엄마 사줄 5만원대 원피스"
            출력: {"gender":"여성","subcat":"원피스","minPrice":50000,"maxPrice":59999,"semanticText":"엄마에게 어울리는 단정한 원피스"}
            """;

    public ParsedQuery extract(String userQuery) {
        try {
            String content = chatClient.prompt()
                    .system(SYSTEM)
                    .user(userQuery)
                    .call()
                    .content();
            return om.readValue(extractJson(content), ParsedQuery.class);
        } catch (Exception e) {
            log.warn("[챗봇] 조건추출 실패, 원문으로 폴백: {} ({})", userQuery, e.getMessage());
            return new ParsedQuery(null, null, null, null, userQuery);
        }
    }

    // 코드블록/여분 텍스트가 섞여 와도 첫 '{' ~ 마지막 '}' 만 취한다.
    private String extractJson(String s) {
        if (s == null) return "{}";
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return (a >= 0 && b >= a) ? s.substring(a, b + 1) : "{}";
    }
}
