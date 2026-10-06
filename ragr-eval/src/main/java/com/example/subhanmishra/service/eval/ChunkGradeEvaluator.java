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
 * measures generation as much as retrieval - a relevant chunk the model ignored scores as noise - and
 * cannot be asked of chunks the model never saw. Retrieval metrics need relevance to the question alone,
 * and the pool beyond top-k is the part recall is about.
 *
 * <p>The middle grade is deliberate. Every candidate is topically close to the question - the vector
 * search chose it for that - and a YES/NO judge given only topical closeness answers YES. Grade 1 gives
 * "on topic but does not answer" somewhere to go, and only grade 2 counts as relevant.
 *
 * <p><b>The judge is asked on a four-point scale and its answer folded onto three.</b> Asked 0/1/2 with
 * "2 = it contains the information an answer needs", this model grades nearly every on-topic chunk 2.
 * Measured 6 Oct 2026 over the 90 pool chunks of a judged golden run, against grades assigned by
 * reading each chunk in full: precision 0.56 - 18 of 41 chunks it called relevant were not - and recall
 * 1.00. Tightening the wording of the same three grades did not move it (0.57), nor did a
 * "could someone answer from only this passage" phrasing (0.57, and recall fell to 0.87). Asking it to
 * name the fact sought before grading swung the other way: precision 0.93, recall 0.57, because it
 * stopped crediting a passage that answers one half of a two-part question. Separating "the whole
 * answer" (3) from "part of the answer" (2) gave it a place for both: precision 0.76, recall 0.96, with
 * most of the remaining disagreements on chunks that were borderline to grade by hand too. Both 3 and 2
 * are stored as 2, so {@link RetrievalRanking} and the stored grades keep their meaning. The longer
 * prompt costs nothing measurable - 0.61 s a chunk against 0.64 s, back to back.
 *
 * <p>The prompt carries no examples. Any taken from the corpus would come from the golden cases, the
 * only text graded by hand, and would make the judge's agreement there overstate its agreement on live
 * traffic.
 *
 * <p>One call per chunk, about 1-3.5 s each on this host (measured 6 Oct 2026: a 124-token prompt in
 * 1.0 s, a 506-token one in 3.5 s; prompt evaluation runs at ~150 tokens/s and the one-digit reply costs
 * 0.08 s). Per chunk rather than one listwise call, because each call is then short enough that a user
 * arriving mid-call waits seconds, not the whole pool.
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
