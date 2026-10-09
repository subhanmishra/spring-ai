package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithfulness as a fraction: the share of the answer's claims the passages support - RAGAS's
 * definition, where {@code FactCheckingEvaluator} gives one YES/NO for the whole answer. A single
 * unsupported sentence fails that judge exactly as a fabricated answer does; this one tells them apart.
 *
 * <p>Two calls, not one per claim: first list the claims from the answer alone, then show the passages
 * once and ask for a verdict per numbered claim. Reading the passages costs ~10 s, so once per claim would
 * cost ~10 s per claim.
 *
 * <p>The price is a longer reply to keep in order. A claim left without a verdict abandons the
 * measurement (null) rather than being guessed.
 */
public class ClaimFaithfulnessEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ClaimFaithfulnessEvaluator.class);

    private static final String EXTRACT_PROMPT = """
            List the factual claims this answer makes, one per line, each as a short sentence that
            stands on its own. Leave out greetings, opinions and advice that states no fact.
            Write at most {max} lines and nothing else. If it makes no factual claim, reply NONE.

            Answer:
            {answer}""";

    private static final String VERIFY_PROMPT = """
            You are checking claims against source passages.

            Passages:
            {passages}

            Claims:
            {claims}

            For each claim, decide whether the passages state or directly imply it.
            Reply with one line per claim, in order, as the claim number followed by SUPPORTED or
            UNSUPPORTED - for example "1 SUPPORTED". Write nothing else.""";

    private static final Pattern VERDICT_LINE = Pattern.compile("(?m)^\\D*(\\d+)\\D*?\\b(SUPPORTED|UNSUPPORTED)\\b");

    private final ChatClient chatClient;
    private final int maxClaims;

    /**
     * @param chatClientBuilder a judge client whose generation cap allows a list, not one word
     * @param maxClaims         the most claims taken from one answer
     */
    public ClaimFaithfulnessEvaluator(ChatClient.Builder chatClientBuilder, int maxClaims) {
        this.chatClient = chatClientBuilder.build();
        this.maxClaims = maxClaims;
    }

    /** The answer's claims, at most {@code maxClaims}; empty when it makes none, null when unreadable. */
    public @Nullable List<String> extractClaims(String answer) {
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(EXTRACT_PROMPT)
                                                      .param("max", String.valueOf(maxClaims))
                                                      .param("answer", answer))
                                    .call()
                                    .content();
        if (response == null) {
            return null;
        }
        if (response.strip().toUpperCase(Locale.ROOT).startsWith("NONE")) {
            return List.of();
        }
        List<String> claims = JudgeText.lines(response);
        return List.copyOf(claims.subList(0, Math.min(maxClaims, claims.size())));
    }

    /** One verdict per claim, in order, or null if any claim was left without one. */
    public @Nullable List<Boolean> verify(List<String> claims, List<Document> passages) {
        if (claims.isEmpty()) {
            return List.of();
        }
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < claims.size(); i++) {
            numbered.append(i + 1).append(". ").append(claims.get(i)).append('\n');
        }
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(VERIFY_PROMPT)
                                                      .param("passages", JudgeText.passages(passages))
                                                      .param("claims", numbered.toString().strip()))
                                    .call()
                                    .content();
        return parseVerdicts(response, claims.size());
    }

    static @Nullable List<Boolean> parseVerdicts(@Nullable String response, int claimCount) {
        if (response == null) {
            return null;
        }
        Boolean[] verdicts = new Boolean[claimCount];
        Matcher matcher = VERDICT_LINE.matcher(response.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1)) - 1;
            if (index >= 0 && index < claimCount && verdicts[index] == null) {
                verdicts[index] = "SUPPORTED".equals(matcher.group(2));
            }
        }
        List<Boolean> result = new ArrayList<>(claimCount);
        for (Boolean verdict : verdicts) {
            if (verdict == null) {
                log.debug("Claim verification left a claim without a verdict: {}", response);
                return null;
            }
            result.add(verdict);
        }
        return result;
    }
}
