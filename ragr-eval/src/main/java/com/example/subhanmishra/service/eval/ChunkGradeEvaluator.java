package com.example.subhanmishra.service.eval;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

/**
 * Grades one retrieved chunk for the <em>question</em>: 0 not useful, 1 related background, 2 holds what
 * the answer needs. The grades over a turn's whole candidate pool are what {@link RetrievalRanking}
 * turns into precision, pooled recall, MRR and NDCG on live traffic, where there is no dataset to say
 * which chunks were right.
 *
 * <p>Not {@link ContextPrecisionEvaluator}, which asks whether the <em>answer used</em> a passage. That
 * measures generation as much as retrieval, and cannot be asked of chunks the model never saw - yet the
 * pool beyond the prompt is exactly what recall is about.
 *
 * <p><b>Why a middle grade.</b> Every candidate is on topic - the search chose it for that - and a YES/NO
 * judge answers YES to anything on topic. Grade 1 gives "on topic, but no answer" somewhere to go; only 2
 * counts as relevant.
 *
 * <p><b>Asked on four points, stored on three.</b> On 0/1/2 the model graded nearly every on-topic chunk
 * 2. Splitting "the whole answer" (3) from "part of the answer" (2) gave the best agreement with grades
 * assigned by hand; both are stored as 2, so stored grades keep their meaning.
 *
 * <p>No examples in the prompt: they could only come from the hand-graded golden cases, and would make
 * agreement there overstate agreement on live traffic.
 *
 * <p>One short call per chunk (1-3.5 s), not one call for the whole pool, so a user arriving mid-call
 * waits seconds, not the whole pool.
 */
public class ChunkGradeEvaluator {

    private static final String PROMPT = """
            You are grading a passage that a search returned for a question. The search finds passages on the
            same topic as the question, so most passages it returns are related without answering it.

            Question:
            {question}

            Passage:
            {passage}

            Grade the passage:
            3 = it states the whole answer.
            2 = it states part of the answer - for example one of the things a two-part question asks.
            1 = it is about the same feature, property or subject, but states no part of the answer.
            0 = it is about something else.

            Reply on one line: "Grade: " and one digit.""";

    private final ChatClient chatClient;

    public ChunkGradeEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** The chunk's grade, 0-2, or null when the judge's reply was not one of the four digits. */
    public @Nullable Integer grade(String question, Document chunk) {
        // Template parameters, not concatenation: the corpus is full of ${...} property placeholders, and a
        // substituted value is not re-rendered whereas prompt text is.
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(PROMPT)
                                                      .param("question", question)
                                                      .param("passage", JudgeText.passage(chunk)))
                                    .call()
                                    .content();
        return gradeOf(response);
    }

    /** The judge's 0-3 reply folded onto the stored 0-2 scale: whole and partial answers are both relevant. */
    static @Nullable Integer gradeOf(@Nullable String response) {
        Integer grade = JudgeText.digit(response, 0, 3);
        return grade == null ? null : Math.min(grade, RetrievalRanking.RELEVANT);
    }
}
