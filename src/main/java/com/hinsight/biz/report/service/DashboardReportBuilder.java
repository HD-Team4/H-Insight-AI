package com.hinsight.biz.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hinsight.biz.reviewanalysis.service.ProductBoostAutomationService.BoostSuggestion;
import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 대시보드 집계 JSON → 리포트 조립. 노션은 table/callout 블록, 메일은 HTML.
// KPI 전기 비교는 JSON 의 optional 필드 totalSalesDelta 가 있으면 표기.
@Component
public class DashboardReportBuilder {

    private static final NumberFormat NF = NumberFormat.getInstance(Locale.KOREA);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int TOP_N = 5;
    private static final String TEAL = "#008982";

    public ReportContent build(String company, JsonNode d, List<BoostSuggestion> suggestions) {
        String period = d.path("period").asText("주간");
        String generatedAt = d.path("generatedAt").asText("-");
        String title = String.format("📊 %s %s 리포트 · %s", company, period, LocalDateTime.now().format(STAMP));

        JsonNode kpi = d.path("kpi");
        JsonNode cats = d.path("categorySales");
        JsonNode top = d.path("topProducts");

        return new ReportContent(title,
                buildNotionBlocks(company, period, generatedAt, kpi, cats, top, suggestions),
                buildHtml(company, period, generatedAt, kpi, cats, top, suggestions));
    }

    private List<Object> buildNotionBlocks(String company, String period, String generatedAt,
                                           JsonNode kpi, JsonNode cats, JsonNode top,
                                           List<BoostSuggestion> suggestions) {
        List<Object> blocks = new ArrayList<>();

        // (헤더는 토글 제목이 대신함) 생성 시각만 안내
        blocks.add(callout("🕒", "집계 생성 " + generatedAt, "gray_background"));

        // 핵심 지표 — 표
        blocks.add(heading2("📈 핵심 지표"));
        List<List<List<Map<String, Object>>>> kpiRows = new ArrayList<>();
        kpiRows.add(row(cell("지표"), cell("값")));
        kpiRows.add(row(cell("총매출"), cell(won(kpi.path("totalSales").asLong()) + "원" + deltaText(kpi))));
        kpiRows.add(row(cell("주문수"), cell(won(kpi.path("orderCount").asLong()) + "건")));
        kpiRows.add(row(cell("판매수량"), cell(won(kpi.path("totalQuantity").asLong()) + "개")));
        blocks.add(table(2, kpiRows));

        // 카테고리 매출 — 표
        if (cats.isArray() && !cats.isEmpty()) {
            blocks.add(heading2("🏷 카테고리 매출"));
            List<List<List<Map<String, Object>>>> rows = new ArrayList<>();
            rows.add(row(cell("카테고리"), cell("매출")));
            for (JsonNode c : cats) {
                rows.add(row(cell(c.path("category").asText()), cell(won(c.path("sales").asLong()) + "원")));
            }
            blocks.add(table(2, rows));
        }

        // 상품 TOP N — 표
        if (top.isArray() && !top.isEmpty()) {
            blocks.add(heading2("🥇 상품 매출 TOP " + TOP_N));
            List<List<List<Map<String, Object>>>> rows = new ArrayList<>();
            rows.add(row(cell("#"), cell("상품"), cell("판매량"), cell("매출"), cell("증감")));
            int i = 0;
            for (JsonNode p : top) {
                if (i++ >= TOP_N) break;
                rows.add(row(
                        cell(String.valueOf(i)),
                        cell(p.path("productName").asText()),
                        cell(won(p.path("unitsSold").asLong())),
                        cell(won(p.path("revenue").asLong()) + "원"),
                        cellColored(growthLabel(p.path("growth")), growthColor(p.path("growth")))));
            }
            blocks.add(table(5, rows));
        }

        // 업무 자동화 제안 — 주간 매출 하락률 TOP 5 표 + 제안 링크
        if (suggestions != null && !suggestions.isEmpty()) {
            blocks.add(heading2("🤖 업무 자동화 제안"));
            List<List<List<Map<String, Object>>>> rows = new ArrayList<>();
            rows.add(row(cell("#"), cell("상품"), cell("주간 매출"), cell("증감"), cell("제안")));
            int i = 0;
            for (BoostSuggestion s : suggestions) {
                if (i++ >= TOP_N) break;
                rows.add(row(
                        cell(String.valueOf(i)),
                        cell(productCellText(s)),
                        cell(won(s.revenue()) + "원"),
                        cellColored(growthLabel(s.growth()), growthColor(s.growth())),
                        cellLink("시도해보기", s.item().actionUrl())));
            }
            blocks.add(table(5, rows));
        }

        blocks.add(divider());
        return blocks;
    }

    private String buildHtml(String company, String period, String generatedAt,
                             JsonNode kpi, JsonNode cats, JsonNode top,
                             List<BoostSuggestion> suggestions) {
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"max-width:720px;margin:0 auto;font-family:'Apple SD Gothic Neo','Malgun Gothic',sans-serif;color:#222\">");

        // 헤더 바
        h.append("<div style=\"background:").append(TEAL).append(";color:#fff;padding:20px 24px;border-radius:12px 12px 0 0\">")
                .append("<div style=\"font-size:18px;font-weight:800\">📊 ").append(esc(company)).append(" · ").append(esc(period)).append(" 리포트</div>")
                .append("<div style=\"font-size:12px;opacity:.85;margin-top:4px\">생성 ").append(esc(generatedAt)).append("</div>")
                .append("</div>");

        h.append("<div style=\"border:1px solid #eee;border-top:none;border-radius:0 0 12px 12px;padding:24px\">");

        // KPI 카드
        h.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-bottom:22px\"><tr>");
        h.append(kpiCard("총매출", won(kpi.path("totalSales").asLong()), "원"));
        h.append(kpiCard("주문수", won(kpi.path("orderCount").asLong()), "건"));
        h.append(kpiCard("판매수량", won(kpi.path("totalQuantity").asLong()), "개"));
        h.append("</tr></table>");

        // 카테고리 표
        if (cats.isArray() && !cats.isEmpty()) {
            h.append(sectionTitle("🏷 카테고리 매출"));
            h.append("<table width=\"100%\" style=\"border-collapse:collapse;font-size:14px;margin-bottom:22px\">");
            h.append("<tr style=\"background:#f6f9f9\">").append(th("카테고리", "left")).append(th("매출", "right")).append("</tr>");
            for (JsonNode c : cats) {
                h.append("<tr>").append(td(esc(c.path("category").asText()), "left", null))
                        .append(td(won(c.path("sales").asLong()) + "원", "right", null)).append("</tr>");
            }
            h.append("</table>");
        }

        // 상품 TOP 표
        if (top.isArray() && !top.isEmpty()) {
            h.append(sectionTitle("🥇 상품 매출 TOP " + TOP_N));
            h.append("<table width=\"100%\" style=\"border-collapse:collapse;font-size:14px;margin-bottom:22px\">");
            h.append("<tr style=\"background:#f6f9f9\">")
                    .append(th("#", "left")).append(th("상품", "left")).append(th("판매량", "right"))
                    .append(th("매출", "right")).append(th("증감", "right")).append("</tr>");
            int i = 0;
            for (JsonNode p : top) {
                if (i++ >= TOP_N) break;
                h.append("<tr>")
                        .append(td(String.valueOf(i), "left", null))
                        .append(td(esc(p.path("productName").asText()), "left", null))
                        .append(td(won(p.path("unitsSold").asLong()), "right", null))
                        .append(td(won(p.path("revenue").asLong()) + "원", "right", null))
                        .append(td(growthLabel(p.path("growth")), "right", growthColor(p.path("growth"))))
                        .append("</tr>");
            }
            h.append("</table>");
        }

        // 업무 자동화 제안 — 주간 매출 하락률 TOP 5 표 + 제안 링크
        if (suggestions != null && !suggestions.isEmpty()) {
            h.append(sectionTitle("🤖 업무 자동화 제안"));
            h.append("<table width=\"100%\" style=\"border-collapse:collapse;font-size:14px;margin-bottom:22px\">");
            h.append("<tr style=\"background:#f6f9f9\">")
                    .append(th("#", "left")).append(th("상품", "left"))
                    .append(th("주간 매출", "right")).append(th("증감", "right"))
                    .append(th("제안", "center")).append("</tr>");
            int i = 0;
            for (BoostSuggestion s : suggestions) {
                if (i++ >= TOP_N) break;
                h.append("<tr>")
                        .append(td(String.valueOf(i), "left", null))
                        .append(td(esc(productCellText(s)).replace("\n", "<br>"), "left", null))
                        .append(td(won(s.revenue()) + "원", "right", null))
                        .append(td(growthLabel(s.growth()), "right", growthColor(s.growth())))
                        .append(td(suggestionLink(s.item().actionUrl()), "center", null))
                        .append("</tr>");
            }
            h.append("</table>");
        }

        h.append("</div></div>");
        return h.toString();
    }

    private String kpiCard(String label, String value, String unit) {
        return "<td style=\"padding:6px;width:33%\">"
                + "<div style=\"background:#f6f9f9;border-radius:10px;padding:16px 12px;text-align:center\">"
                + "<div style=\"font-size:12px;color:#888\">" + esc(label) + "</div>"
                + "<div style=\"font-size:20px;font-weight:800;color:" + TEAL + ";margin-top:6px\">"
                + value + "<span style=\"font-size:12px;font-weight:600;color:#888\">" + unit + "</span></div>"
                + "</div></td>";
    }

    private String sectionTitle(String t) {
        return "<div style=\"font-size:15px;font-weight:800;margin:0 0 10px;padding-left:8px;border-left:3px solid " + TEAL + "\">" + esc(t) + "</div>";
    }

    private String th(String t, String align) {
        return "<th align=\"" + align + "\" style=\"padding:9px 8px;font-size:12px;color:#666;border-bottom:2px solid #e5e5e5\">" + esc(t) + "</th>";
    }

    private String td(String t, String align, String color) {
        String c = color != null ? ";color:" + color + ";font-weight:700" : "";
        return "<td align=\"" + align + "\" style=\"padding:9px 8px;border-bottom:1px solid #f0f0f0" + c + "\">" + t + "</td>";
    }

    private String productCellText(BoostSuggestion suggestion) {
        String category = suggestion.item().category();
        if (category == null || category.isBlank()) {
            return suggestion.item().productName();
        }
        return suggestion.item().productName() + "\n" + category;
    }

    private String suggestionLink(String url) {
        return "<a href=\"" + esc(url) + "\" style=\"color:" + TEAL
                + ";font-size:13px;font-weight:800;text-decoration:none\">시도해보기</a>";
    }

    private String deltaText(JsonNode kpi) {
        if (kpi.hasNonNull("totalSalesDelta")) {
            double delta = kpi.path("totalSalesDelta").asDouble();
            return String.format("  (전기 대비 %s%.1f%%)", delta >= 0 ? "▲" : "▼", Math.abs(delta));
        }
        return "";
    }

    private String growthLabel(JsonNode growth) {
        if (growth == null || growth.isNull()) return "신규";
        long g = growth.asLong();
        return g >= 0 ? "▲" + g + "%" : "▼" + Math.abs(g) + "%";
    }

    private String growthLabel(Integer growth) {
        if (growth == null) return "신규";
        return growth >= 0 ? "▲" + growth + "%" : "▼" + Math.abs(growth) + "%";
    }

    // 노션 컬러명(red/blue/gray)
    private String growthColor(JsonNode growth) {
        if (growth == null || growth.isNull()) return "gray";
        return growth.asLong() >= 0 ? "red" : "blue";
    }

    private String growthColor(Integer growth) {
        if (growth == null) return "gray";
        return growth >= 0 ? "red" : "blue";
    }

    private String won(long n) {
        return NF.format(n);
    }

    private Map<String, Object> heading2(String t) {
        return Map.of("type", "heading_2", "heading_2", Map.of("rich_text", rt(t)));
    }

    private Map<String, Object> callout(String emoji, String content, String color) {
        return Map.of("type", "callout", "callout", Map.of(
                "rich_text", rt(content),
                "icon", Map.of("type", "emoji", "emoji", emoji),
                "color", color));
    }

    private Map<String, Object> divider() {
        return Map.of("type", "divider", "divider", Map.of());
    }

    // width = 열 개수, rows[0] 은 헤더행
    private Map<String, Object> table(int width, List<List<List<Map<String, Object>>>> rows) {
        List<Object> children = new ArrayList<>();
        for (List<List<Map<String, Object>>> cells : rows) {
            children.add(Map.of("type", "table_row", "table_row", Map.of("cells", cells)));
        }
        return Map.of("type", "table", "table", Map.of(
                "table_width", width,
                "has_column_header", true,
                "has_row_header", false,
                "children", children));
    }

    @SafeVarargs
    private List<List<Map<String, Object>>> row(List<Map<String, Object>>... cells) {
        return List.of(cells);
    }

    private List<Map<String, Object>> cell(String content) {
        return rt(content);
    }

    private List<Map<String, Object>> cellColored(String content, String color) {
        return List.of(Map.of("type", "text", "text", Map.of("content", content),
                "annotations", Map.of("color", color)));
    }

    private List<Map<String, Object>> cellLink(String content, String url) {
        return List.of(Map.of("type", "text", "text", Map.of("content", content, "link", Map.of("url", url)),
                "annotations", Map.of("bold", true, "color", "blue")));
    }

    private List<Map<String, Object>> rt(String content) {
        return List.of(Map.of("type", "text", "text", Map.of("content", content)));
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record ReportContent(String title, List<Object> notionBlocks, String htmlBody) {
    }
}
