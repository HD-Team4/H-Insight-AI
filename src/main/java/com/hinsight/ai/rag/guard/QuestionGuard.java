package com.hinsight.ai.rag.guard;

import org.ahocorasick.trie.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 리뷰봇 <b>앞단(Aggregator 진입 전) 가드레일</b>. 임베딩/집계/LLM 으로 넘어가기 전에
 * 값싼 결정적(deterministic) 규칙으로 걸러 <b>즉시 파기(Drop)</b>한다.
 *
 * <p>금칙어 탐지는 외부 API 없이 서버 메모리에 사전을 올려 <b>Aho-Corasick</b> 로 스캔한다.
 * 수만 개 금칙어도 입력 길이에 비례한 O(N) 1스캔으로 처리되어 네트워크 지연이 없고(1ms 이내),
 * STOMP 수신 스레드에서 직접 실행해도 무방하다. 사전은 {@code classpath:rag/badwords.txt} 에서 로딩한다.</p>
 *
 * <ul>
 *   <li>{@link Decision#DROP_NOT_QUESTION} : 질문 형태가 아니거나 너무 짧음.</li>
 *   <li>{@link Decision#DROP_ABUSE} : 욕설·비방·트롤링(예: "호스트 바보인가요?"). 아무리 반복돼도 답하지 않는다.</li>
 *   <li>{@link Decision#ACCEPT} : 상품 문의로 볼 수 있어 집계 단계로 통과.</li>
 * </ul>
 *
 * <p>한계: 문맥은 파악하지 못한다. 여기서 못 거르는 "정중하지만 상품과 무관한" 질문은 통과되더라도,
 * 집계 트리거 후 RAG 컨텍스트에 근거가 없으면 LLM 이 IGNORE 를 반환하고 봇이 Silent Drop 한다.</p>
 */
@Component
public class QuestionGuard {

    private static final Logger log = LoggerFactory.getLogger(QuestionGuard.class);

    public enum Decision {
        ACCEPT,
        DROP_NOT_QUESTION,
        DROP_ABUSE
    }

    /** 한국어 의문 힌트 (물음표가 없어도 질문으로 간주). */
    private static final List<String> QUESTION_HINTS = List.of(
            "어때", "어떤", "어떠", "어떰", "나요", "까요", "얼마", "있나", "없나", "되나", "될까",
            "인가", "궁금", "어디", "언제", "몇", "가요", "니까", "은지", "는지", "아닌",
            // 간접의문·구어체
            "냐고", "냐요", "느냐", "냐", "래요", "린가", "려나", "ㄴ지");

    /** 사전 파일 로딩 실패 시 최소 방어용 폴백. (정상 경로는 classpath:rag/badwords.txt) */
    private static final List<String> FALLBACK_ABUSE = List.of(
            "바보", "멍청", "병신", "시발", "씨발", "존나", "개새", "꺼져", "죽어", "닥쳐", "쓰레기", "지랄");

    private final Trie abuseTrie;   // 불변, 스레드-세이프. 앱 부팅 시 1회 구축.

    @Value("${rag.guard.min-length:2}") private int minLength;

    public QuestionGuard(@Value("${rag.guard.badwords-resource:rag/badwords.txt}") String badwordsResource) {
        List<String> words = loadBadwords(badwordsResource);
        // ignoreCase: 대소문자 무시. onlyWholeWords 는 쓰지 않는다(한국어 부분일치가 목적).
        this.abuseTrie = Trie.builder().ignoreCase().addKeywords(words).build();
        log.info("[가드] 금칙어 사전 로딩 완료: {}개 (source={})", words.size(), badwordsResource);
    }

    /** 메시지 1건을 판정한다. STOMP 스레드에서 매 채팅마다 호출되므로 가볍게 유지한다. */
    public Decision classify(String rawMessage) {
        if (rawMessage == null) return Decision.DROP_NOT_QUESTION;

        String q = rawMessage.strip();
        String norm = normalize(q);
        if (norm.length() < minLength) return Decision.DROP_NOT_QUESTION;

        if (containsAbuse(norm)) return Decision.DROP_ABUSE;   // 비방·트롤링은 질문이어도 파기
        if (!looksLikeQuestion(q)) return Decision.DROP_NOT_QUESTION;

        return Decision.ACCEPT;
    }

    /** 정규화된 입력을 Aho-Corasick 로 1회 스캔. 금칙어가 하나라도 포함되면 true. */
    private boolean containsAbuse(String normalized) {
        return abuseTrie.containsMatch(normalized);
    }

    private boolean looksLikeQuestion(String s) {
        if (s.contains("?") || s.contains("？")) return true;
        return QUESTION_HINTS.stream().anyMatch(s::contains);
    }

    /** 소문자화 + 공백/문장부호 제거 ("바 보?", "시-발" 같은 우회 표기까지 잡기 위함). */
    private String normalize(String s) {
        return s.strip().toLowerCase().replaceAll("[\\s?!.~。？！\\-_*]+", "");
    }

    /** classpath 사전 로딩. '#' 주석·빈 줄은 무시하고 소문자로 정규화해 담는다. 실패 시 폴백 목록. */
    private List<String> loadBadwords(String resourcePath) {
        List<String> words = new ArrayList<>();
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String w = line.strip().toLowerCase();
                if (w.isEmpty() || w.startsWith("#")) continue;
                words.add(w);
            }
        } catch (Exception e) {
            log.warn("[가드] 금칙어 사전 로딩 실패, 폴백 목록 사용: {} ({})", resourcePath, e.getMessage());
            return FALLBACK_ABUSE;
        }
        return words.isEmpty() ? FALLBACK_ABUSE : words;
    }
}
