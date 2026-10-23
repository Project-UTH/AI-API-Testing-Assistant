package com.aiapitesting.backend.service.ai;

import com.aiapitesting.backend.dto.response.RulesJobResult;
import com.aiapitesting.backend.entity.Endpoint;
import com.aiapitesting.backend.entity.Project;
import com.aiapitesting.backend.entity.RequirementDocument;
import com.aiapitesting.backend.entity.RuleSource;
import com.aiapitesting.backend.entity.TestGenerationEvent;
import com.aiapitesting.backend.repository.TestGenerationEventRepository;
import com.aiapitesting.backend.repository.UserRepository;
import com.aiapitesting.backend.service.TestCaseChecklistPlanner;
import com.aiapitesting.backend.service.job.JobProgress;
import com.aiapitesting.backend.service.rule.BusinessRuleService;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.EndpointInfo;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Proposed;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Source;
import com.aiapitesting.backend.service.rule.RuleExtractionVerifier.Verdict;
import com.aiapitesting.backend.service.rule.RuleParams;
import com.aiapitesting.backend.service.rule.RuleProposalWriter;
import com.aiapitesting.backend.service.rule.TextSecretScanner;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 17q G3.3 - AI TRÍCH luật nghiệp vụ từ tài liệu/chỉ dẫn của người dùng. AI chỉ đề xuất; mọi thứ qua {@link RuleExtractionVerifier} rồi
 * {@link RuleProposalWriter} (luật nằm ở PROPOSED, người dùng duyệt). Chạy theo LÔ endpoint để mỗi lệnh gọi nhỏ, và có NGÂN SÁCH TOKEN CỨNG: trước mỗi
 * lệnh gọi ước lượng token vào + trần token ra, không đủ thì thu nhỏ phần tài liệu, vẫn không đủ thì dừng và GIỮ phần đã làm.
 * Nội dung tài liệu không bao giờ vào log/bước giám sát (chỉ đếm).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleExtractionService {

    public static final int DEFAULT_BUDGET = 60_000;
    public static final int MIN_BUDGET = 5_000;
    public static final int MAX_BUDGET = 1_000_000;
    public static final String EVENT_TYPE = "RULE_EXTRACT";
    static final int MAX_OUTPUT_TOKENS = 4_000;
    static final int MIN_USEFUL_OUTPUT = 1_500;
    static final int LOT_SIZE = 12;
    static final int DOC_CHAR_LIMIT = 40_000;
    /** Tiếng Việt ~2,8 ký tự/token, cộng 10% dư. */
    static final double TOKENS_PER_CHAR = 1.1 / 2.8;
    static final String BUDGET_STOPPED = "BUDGET_STOPPED";
    static final String DAILY_QUOTA = "DAILY_QUOTA";
    static final String AI_ERROR = "AI_ERROR";

    private final ChatClient chatClient;
    private final TestGenerationEventRepository events;
    private final AiQuotaService quota;
    private final UserRepository users;
    private final TestCaseChecklistPlanner planner;
    private final RuleProposalWriter writer;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public record Request(
            Project project, UUID ownerId, List<Endpoint> endpoints, List<RequirementDocument> documents, String instruction,
            boolean allowInferred, UUID jobId, Integer tokenCap
    ) {
    }

    public static int budgetOf(Integer tokenCap) {
        return tokenCap == null || tokenCap <= 0 ? DEFAULT_BUDGET : Math.max(MIN_BUDGET, Math.min(MAX_BUDGET, tokenCap));
    }

    static int estimateTokens(int chars) {
        return (int) Math.ceil(chars * TOKENS_PER_CHAR);
    }

    // ---- chạy ----

    public RulesJobResult run(Request req, JobProgress ctx) {
        int budget = budgetOf(req.tokenCap());
        String instruction = req.instruction() == null ? "" : req.instruction().trim();
        List<Doc> docs = req.documents().stream()
                .map(d -> new Doc(d.getId(), d.getFileName(), d.getContent(), TextSecretScanner.mask(d.getContent()))).toList();
        String maskedInstruction = TextSecretScanner.mask(instruction);

        JobProgress.Step read = ctx.start(null, "Tài liệu", "DOC_READ", "SYSTEM", "Đọc tài liệu và chỉ dẫn");
        int docChars = docs.stream().mapToInt(d -> d.masked().length()).sum();
        read.ok(docs.size() + " tài liệu (" + docChars + " ký tự)" + (instruction.isEmpty() ? "" : ", có chỉ dẫn")
                + "; chuỗi giống bí mật được che trước khi gửi AI");

        List<List<Endpoint>> lots = planLots(req.endpoints());
        String templates = readResource("prompts/rules/templates.st");
        int fixedChars = readResource("prompts/rules/extract.st").length() + templates.length() + maskedInstruction.length();
        int estimated = 0;
        for (List<Endpoint> lot : lots) {
            estimated += estimateTokens(fixedChars + digestOf(lot).length() + Math.min(DOC_CHAR_LIMIT, docChars)) + MAX_OUTPUT_TOKENS;
        }
        JobProgress.Step plan = ctx.start(null, "Kế hoạch", "RULES_PLAN", "SYSTEM", "Chia lô và ước tính token");
        plan.ok(req.endpoints().size() + " endpoint chia " + lots.size() + " lô; ước tính tối đa ~" + estimated + " token, ngân sách " + budget + " token");
        if (ctx.isDryRun()) {
            return new RulesJobResult(req.endpoints().size(), lots.size(), 0, estimated, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), null, budget);
        }

        List<Source> sources = new ArrayList<>();
        for (Doc d : docs) {
            sources.add(new Source(d.id(), d.name(), d.original(), RuleSource.DOC));
        }
        if (!instruction.isEmpty()) {
            sources.add(new Source(null, "chỉ dẫn", instruction, RuleSource.INSTRUCTION));
        }

        Totals totals = new Totals();
        int done = 0;
        String stopped = null;
        for (int i = 0; i < lots.size() && stopped == null; i++) {
            ctx.checkCancelled();
            String label = "Lô " + (i + 1) + "/" + lots.size();
            stopped = runLot(req, ctx, lots.get(i), label, docs, maskedInstruction, templates, sources, budget, totals);
            if (stopped == null) {
                done++;
            }
        }
        return totals.result(req.endpoints().size(), lots.size(), done, estimated, stopped, budget);
    }

    /** @return null nếu lô chạy xong; lý do dừng nếu phải dừng cả job. */
    private String runLot(Request req, JobProgress ctx, List<Endpoint> lot, String label, List<Doc> docs, String maskedInstruction, String templates,
                          List<Source> sources, int budget, Totals totals) {
        String nonce = UUID.randomUUID().toString().substring(0, 8);
        String digest = digestOf(lot);
        long remainingQuota = quota.remainingToday(users.findById(req.ownerId()).orElseThrow());
        long allowed = Math.min(budget - ctx.tokensUsed(), remainingQuota);
        String reason = remainingQuota <= budget - ctx.tokensUsed() ? DAILY_QUOTA : BUDGET_STOPPED;

        int fixedEstimate = estimateTokens(renderPrompt(nonce, templates, digest, maskedInstruction, "").length());
        long forDocs = allowed - MAX_OUTPUT_TOKENS - fixedEstimate;
        if (forDocs < 1_500) {
            ctx.start(null, label, "RULES_AI", "AI", "Gọi AI trích luật").skipped(
                    "Dừng: " + (DAILY_QUOTA.equals(reason) ? "hết quota token trong ngày" : "chạm ngân sách token") + " - giữ phần đã làm");
            return reason;
        }
        int totalChars = docs.stream().mapToInt(d -> d.masked().length()).sum();
        int charLimit = (int) Math.min(DOC_CHAR_LIMIT, Math.floor(forDocs / TOKENS_PER_CHAR));
        String documentsText = selectDocumentText(docs, lot, charLimit);
        String promptText = renderPrompt(nonce, templates, digest, maskedInstruction, documentsText);
        int estimatedIn = estimateTokens(promptText.length());
        long maxOut = Math.min(MAX_OUTPUT_TOKENS, allowed - estimatedIn);
        if (maxOut < MIN_USEFUL_OUTPUT) {
            ctx.start(null, label, "RULES_AI", "AI", "Gọi AI trích luật").skipped("Dừng: không đủ token cho phần trả lời - giữ phần đã làm");
            return reason;
        }

        JobProgress.Step ai = ctx.start(null, label, "RULES_AI", "AI", "AI trích luật cho " + lot.size() + " endpoint");
        String text;
        int tokens;
        try {
            ChatResponse response = chatClient.prompt(promptText)
                    .options(AnthropicChatOptions.builder().maxTokens((int) maxOut).thinkingDisabled())
                    .call().chatResponse();
            text = response == null || response.getResult() == null ? "" : response.getResult().getOutput().getText();
            Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
            tokens = usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0
                    ? usage.getTotalTokens() : estimatedIn + estimateTokens(text == null ? 0 : text.length());
        } catch (RuntimeException e) {
            log.warn("Goi AI trich luat that bai ({}): {}", label, e.getClass().getSimpleName());
            ai.fail("Gọi AI thất bại: " + e.getClass().getSimpleName());
            return AI_ERROR;
        }
        ctx.addTokens(tokens);
        boolean trimmed = charLimit < Math.min(totalChars, DOC_CHAR_LIMIT) || documentsText.length() < totalChars;
        ai.finish("OK", "Đã nhận trả lời" + (trimmed ? " (tài liệu được rút gọn theo ngân sách)" : ""), null, tokens);

        JobProgress.Step verify = ctx.start(null, label, "RULES_VERIFY", "SYSTEM", "Kiểm chứng đầu ra AI (tất định)");
        int proposedCount = 0;
        try {
            ParsedAnswer parsed = parse(text);
            Map<String, EndpointInfo> infos = new HashMap<>();
            Map<String, Endpoint> byKey = new HashMap<>();
            for (Endpoint e : lot) {
                String key = BusinessRuleService.keyOf(e);
                infos.put(key, new EndpointInfo(planner.readSpec(e), e.getMethod()));
                byKey.put(key, e);
            }
            List<Verdict> verdicts = new ArrayList<>();
            int skippedInferred = 0;
            for (Proposed p : parsed.rules()) {
                Verdict v = RuleExtractionVerifier.verify(p, infos, sources);
                if (v.dropped() == null && v.source() == RuleSource.INFERRED && !req.allowInferred()) {
                    skippedInferred++;
                    continue;
                }
                verdicts.add(v);
            }
            RuleProposalWriter.Outcome outcome = writer.write(req.project(), byKey, verdicts, req.jobId(), RuleProposalWriter.DEFAULT_MAX_PER_ENDPOINT);
            proposedCount = outcome.proposed();
            totals.add(outcome, skippedInferred, parsed.malformed());
            verify.ok("AI đề xuất " + parsed.rules().size() + " luật: giữ " + outcome.proposed() + " (" + outcome.fromDocument() + " có trích dẫn tài liệu, "
                    + outcome.fromInstruction() + " từ chỉ dẫn, " + outcome.inferred() + " GIẢ ĐỊNH)"
                    + droppedText(outcome, skippedInferred, parsed.malformed()));
        } catch (RuntimeException e) {
            log.warn("Kiem chung dau ra AI that bai ({}): {}", label, e.getClass().getSimpleName());
            verify.fail("Không đọc được trả lời của AI (định dạng sai) - token đã tính, lô này không có luật mới");
        } finally {
            recordEvent(req, tokens, proposedCount, lot.size());
        }
        return null;
    }

    private static String droppedText(RuleProposalWriter.Outcome o, int skippedInferred, int malformed) {
        int dropped = o.dropped().values().stream().mapToInt(Integer::intValue).sum() + malformed;
        StringBuilder sb = new StringBuilder();
        if (dropped > 0) {
            sb.append("; bỏ ").append(dropped).append(" luật sai endpoint/mẫu/field");
        }
        if (skippedInferred > 0) {
            sb.append("; bỏ ").append(skippedInferred).append(" luật GIẢ ĐỊNH (không nhận luật không có trích dẫn)");
        }
        if (o.skippedExisting() > 0) {
            sb.append("; ").append(o.skippedExisting()).append(" luật đã có sẵn");
        }
        if (o.skippedOverCap() > 0) {
            sb.append("; ").append(o.skippedOverCap()).append(" luật vượt trần mỗi endpoint");
        }
        return sb.toString();
    }

    private void recordEvent(Request req, int tokens, int proposed, int endpointCount) {
        try {
            events.save(TestGenerationEvent.builder()
                    .ownerId(req.ownerId()).projectId(req.project().getId()).eventType(EVENT_TYPE)
                    .testCaseCount(proposed).snapshotJson("{\"endpoints\":" + endpointCount + "}")
                    .totalTokens(tokens).build());
        } catch (RuntimeException e) {
            log.warn("Khong ghi duoc so token trich luat: {}", e.getClass().getSimpleName());
        }
    }

    // ---- lô, tóm tắt endpoint, chọn đoạn tài liệu ----

    /** Gom endpoint theo đoạn đường dẫn đầu tiên (cùng tài nguyên thì chung lô), mỗi lô tối đa {@value #LOT_SIZE}. */
    static List<List<Endpoint>> planLots(List<Endpoint> endpoints) {
        Map<String, List<Endpoint>> groups = new TreeMap<>();
        for (Endpoint e : endpoints) {
            groups.computeIfAbsent(firstSegment(e.getPath()), k -> new ArrayList<>()).add(e);
        }
        List<List<Endpoint>> lots = new ArrayList<>();
        List<Endpoint> current = new ArrayList<>();
        for (List<Endpoint> group : groups.values()) {
            for (Endpoint e : group) {
                if (current.size() >= LOT_SIZE) {
                    lots.add(current);
                    current = new ArrayList<>();
                }
                current.add(e);
            }
        }
        if (!current.isEmpty()) {
            lots.add(current);
        }
        return lots;
    }

    /** Đoạn đường dẫn đầu tiên, bỏ tiền tố chung kiểu /api, /v1. */
    private static String firstSegment(String path) {
        for (String part : (path == null ? "" : path).split("/")) {
            String p = part.toLowerCase(Locale.ROOT);
            if (!p.isEmpty() && !p.equals("api") && !p.matches("v\\d+")) {
                return p;
            }
        }
        return "";
    }

    private String digestOf(List<Endpoint> lot) {
        StringBuilder sb = new StringBuilder();
        for (Endpoint e : lot) {
            sb.append(BusinessRuleService.keyOf(e));
            if (e.getSummary() != null && !e.getSummary().isBlank()) {
                sb.append(" - ").append(e.getSummary().trim());
            }
            sb.append('\n');
            for (TestCaseChecklistPlanner.FieldRule f : planner.readSpec(e).rules()) {
                sb.append("   ").append(f.location() == TestCaseChecklistPlanner.ParamLocation.BODY ? f.name()
                        : f.location().name().toLowerCase(Locale.ROOT) + ":" + f.name()).append(':').append(f.type());
                if (f.required()) {
                    sb.append(" [req]");
                }
                if (f.minimum() != null || f.maximum() != null) {
                    sb.append(" [").append(f.minimum() == null ? "" : f.minimum().toPlainString()).append("..")
                            .append(f.maximum() == null ? "" : f.maximum().toPlainString()).append(']');
                }
                if (f.enumValues() != null && !f.enumValues().isEmpty()) {
                    sb.append(" [enum]");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private record Doc(UUID id, String name, String original, String masked) {
    }

    private record Para(int docIndex, int index, String text, int score) {
    }

    /** Tài liệu vừa giới hạn thì lấy hết; vượt giới hạn thì chọn các đoạn văn nhắc nhiều nhất tới đường dẫn của lô, giữ nguyên thứ tự gốc. */
    private static String selectDocumentText(List<Doc> docs, List<Endpoint> lot, int charLimit) {
        int total = docs.stream().mapToInt(d -> d.masked().length()).sum();
        if (total <= charLimit) {
            StringBuilder all = new StringBuilder();
            for (Doc d : docs) {
                all.append("### ").append(d.name()).append('\n').append(d.masked()).append("\n\n");
            }
            return all.toString();
        }
        List<String> keywords = new ArrayList<>();
        for (Endpoint e : lot) {
            for (String seg : (e.getPath() == null ? "" : e.getPath()).split("[/{}]")) {
                if (seg.length() >= 3) {
                    keywords.add(seg.toLowerCase(Locale.ROOT));
                }
            }
        }
        List<Para> paras = new ArrayList<>();
        for (int d = 0; d < docs.size(); d++) {
            String[] parts = docs.get(d).masked().split("\n\\s*\n");
            for (int i = 0; i < parts.length; i++) {
                String lower = parts[i].toLowerCase(Locale.ROOT);
                int score = 0;
                for (String k : keywords) {
                    int at = lower.indexOf(k);
                    while (at >= 0) {
                        score++;
                        at = lower.indexOf(k, at + k.length());
                    }
                }
                paras.add(new Para(d, i, parts[i], score));
            }
        }
        List<Para> byScore = new ArrayList<>(paras);
        byScore.sort((a, b) -> b.score() != a.score() ? Integer.compare(b.score(), a.score()) : Integer.compare(a.index(), b.index()));
        List<Para> chosen = new ArrayList<>();
        int used = 0;
        for (Para p : byScore) {
            if (used + p.text().length() + 2 > charLimit) {
                continue;
            }
            chosen.add(p);
            used += p.text().length() + 2;
        }
        chosen.sort((a, b) -> a.docIndex() != b.docIndex() ? Integer.compare(a.docIndex(), b.docIndex()) : Integer.compare(a.index(), b.index()));
        StringBuilder out = new StringBuilder();
        int lastDoc = -1;
        for (Para p : chosen) {
            if (p.docIndex() != lastDoc) {
                out.append("### ").append(docs.get(p.docIndex()).name()).append('\n');
                lastDoc = p.docIndex();
            }
            out.append(p.text()).append("\n\n");
        }
        return out.toString();
    }

    // ---- prompt + parse ----

    private String renderPrompt(String nonce, String templates, String endpoints, String instruction, String documents) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("nonce", nonce);
        vars.put("templates", templates);
        vars.put("endpoints", endpoints);
        vars.put("instruction", instruction.isBlank() ? "(không có)" : instruction);
        vars.put("documents", documents.isBlank() ? "(không có tài liệu)" : documents);
        vars.put("maxPerEndpoint", RuleProposalWriter.DEFAULT_MAX_PER_ENDPOINT);
        return PromptTemplate.builder()
                .template(readResource("prompts/rules/extract.st"))
                .renderer(StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build())
                .build().render(vars);
    }

    private static String readResource(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được prompt " + path, e);
        }
    }

    record ParsedAnswer(List<Proposed> rules, int malformed) {
    }

    /** Đọc JSON AI trả (bỏ rào ```), từng luật hỏng được đếm chứ không làm hỏng cả lô. Ném lỗi nếu không có JSON nào. */
    ParsedAnswer parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("empty");
        }
        int from = text.indexOf('{');
        int to = text.lastIndexOf('}');
        if (from < 0 || to <= from) {
            throw new IllegalArgumentException("no json");
        }
        try {
            JsonNode root = mapper.readTree(text.substring(from, to + 1));
            JsonNode rules = root.get("rules");
            if (rules == null || !rules.isArray()) {
                throw new IllegalArgumentException("no rules");
            }
            List<Proposed> list = new ArrayList<>();
            int malformed = 0;
            for (JsonNode node : rules) {
                try {
                    RuleParams params = node.hasNonNull("params") ? mapper.treeToValue(node.get("params"), RuleParams.class) : null;
                    Integer status = node.hasNonNull("expectedStatus") && node.get("expectedStatus").canConvertToInt()
                            ? node.get("expectedStatus").asInt() : null;
                    list.add(new Proposed(textOf(node, "endpoint"), textOf(node, "template"), params, status, textOf(node, "quote"),
                            textOf(node, "rationale")));
                } catch (Exception e) {
                    malformed++;
                }
            }
            return new ParsedAnswer(list, malformed);
        } catch (IOException e) {
            throw new IllegalArgumentException("bad json", e);
        }
    }

    private static String textOf(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static final class Totals {
        int doc;
        int instruction;
        int inferred;
        int dupSpec;
        int conflicts;
        int existing;
        int overCap;
        int skippedInferred;
        int malformed;
        final Map<RuleExtractionVerifier.DropReason, Integer> dropped = new EnumMap<>(RuleExtractionVerifier.DropReason.class);

        void add(RuleProposalWriter.Outcome o, int skipInferred, int bad) {
            doc += o.fromDocument();
            instruction += o.fromInstruction();
            inferred += o.inferred();
            dupSpec += o.rejectedAsDuplicateOfSpec();
            conflicts += o.conflicts();
            existing += o.skippedExisting();
            overCap += o.skippedOverCap();
            skippedInferred += skipInferred;
            malformed += bad;
            o.dropped().forEach((k, v) -> dropped.merge(k, v, Integer::sum));
        }

        RulesJobResult result(int endpoints, int lots, int lotsDone, int estimated, String stopped, int budget) {
            Map<String, Integer> droppedByName = new LinkedHashMap<>();
            dropped.forEach((k, v) -> droppedByName.put(k.name(), v));
            if (malformed > 0) {
                droppedByName.put("MALFORMED", malformed);
            }
            return new RulesJobResult(endpoints, lots, lotsDone, estimated, doc, instruction, inferred, dupSpec, conflicts, existing, overCap,
                    skippedInferred, droppedByName, stopped, budget);
        }
    }
}
