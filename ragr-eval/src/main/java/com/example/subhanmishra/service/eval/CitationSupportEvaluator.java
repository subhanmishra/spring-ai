package com.example.subhanmishra.service.eval;

import com.example.subhanmishra.citation.AnswerCitations;
import com.example.subhanmishra.citation.Citation;
import com.example.subhanmishra.citation.CitationParser;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether each citation's page actually says what the sentence citing it claims.
 *
 * <p>A <em>valid</em> citation only names a page the model was shown. It can still be wrong - the page of
 * one fact beside a sentence stating another - and only reading the two side by side shows that.
 *
 * <p>Only valid citations are checked (a fabricated one has no passage, and is already counted). Each
 * check is one sentence and one passage, at most {@code maxChecks} per answer.
 */
public class CitationSupportEvaluator {

    private static final String PROMPT = """
            You are checking a citation in an answer.

            Sentence from the answer:
            {sentence}

            The passage it cites:
            {passage}

            Does the passage support what the sentence says?
            Reply with exactly one word: YES or NO.""";

    /** Sentence ends, or line breaks - Markdown answers often put one claim per bullet with no full stop. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z*\\-`(])|\\n+");

    private final ChatClient chatClient;
    private final int maxChecks;

    public CitationSupportEvaluator(ChatClient.Builder chatClientBuilder, int maxChecks) {
        this.chatClient = chatClientBuilder.build();
        this.maxChecks = maxChecks;
    }

    /**
     * One citing sentence and the passage it names, ready to judge.
     *
     * @param sentence the sentence as written, citation included
     * @param passage  the in-context chunk whose header the citation matches
     */
    public record Check(String sentence, Document passage) {
    }

    /** Every valid citation in the answer paired with its sentence and passage, at most {@code maxChecks}. */
    public List<Check> checks(String answer, List<Document> inContext) {
        List<Citation> available = CitationParser.availableCitations(inContext);
        Set<String> fileNames = CitationParser.availableFileNames(inContext);
        List<Check> checks = new ArrayList<>();
        for (String sentence : SENTENCE_BREAK.split(answer)) {
            for (Citation citation : CitationParser.parseAnswerCandidates(sentence)) {
                if (checks.size() >= maxChecks) {
                    return List.copyOf(checks);
                }
                if (!AnswerCitations.isCitation(citation, fileNames)
                        || !AnswerCitations.isSupported(citation, available, fileNames)) {
                    continue;
                }
                Document passage = passageFor(citation, inContext);
                if (passage != null) {
                    checks.add(new Check(sentence.strip(), passage));
                }
            }
        }
        return List.copyOf(checks);
    }

    public @Nullable Boolean judge(Check check) {
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(PROMPT)
                                                      .param("sentence", check.sentence())
                                                      .param("passage", JudgeText.passage(check.passage())))
                                    .call()
                                    .content();
        return JudgeText.yesNo(response);
    }

    /** The first in-context chunk whose citation header the citation names. */
    private static @Nullable Document passageFor(Citation citation, List<Document> inContext) {
        for (Document document : inContext) {
            Citation header = CitationParser.parseHeader(document.getText());
            if (header != null && citation.matches(header)) {
                return document;
            }
        }
        return null;
    }
}
