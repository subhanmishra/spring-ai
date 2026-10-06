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
 * <p>One call per chunk, about 1-3.5 s each on this host (measured 6 Oct 2026: a 124-token prompt in
 * 1.0 s, a 506-token one in 3.5 s; prompt evaluation runs at ~150 tokens/s and the one-digit reply costs
 * 0.08 s). Per chunk rather than one listwise call, because each call is then short enough that a user
 * arriving mid-call waits seconds, not the whole pool.
 */
public class ChunkGradeEvaluator {

    private static final String PROMPT = """
            You are grading a passage that a search returned for a question.

            Question:
            {question}

            Passage:
            {passage}

            How useful is this passage for answering the question?
            2 = it contains the information an answer needs
            1 = it is on the same topic but does not answer the question
            0 = it is not useful
            Reply with exactly one digit: 0, 1 or 2.""";

    private final ChatClient chatClient;

    public ChunkGradeEvaluator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** The chunk's grade, or null when the judge's reply was not one of the three digits. */
    public @Nullable Integer grade(String question, Document chunk) {
        // Template parameters, not concatenation: the corpus is full of ${...} property placeholders, and a
        // substituted value is not re-rendered whereas prompt text is.
        String response = chatClient.prompt()
                                    .user(spec -> spec.text(PROMPT)
                                                      .param("question", question)
                                                      .param("passage", JudgeText.passage(chunk)))
                                    .call()
                                    .content();
        return JudgeText.digit(response, 0, 2);
    }
}
