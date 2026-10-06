-- V1__Eval_Schema.sql
--
-- Runs under ragr-eval's Flyway, with the eval schema as its default, so every unqualified name below
-- resolves to eval. Evaluation keeps its results in their own schema, apart from ragr-app's tables in
-- public.
--
-- Two kinds of data live here, with different lifetimes:
--
-- * Golden runs (eval_run, eval_case_result): discrete, comparable runs of a fixed dataset - the thing
--   to diff across a pipeline change. Kept indefinitely.
-- * Evaluated turns (eval_turn, eval_turn_chunk, eval_feedback): every chat turn ragr-eval receives,
--   live or golden, with its deterministic scores, the judges' verdicts, the user's rating and any human
--   review. Live rows are purged after app.eval.retention.live-days by EvalRetentionService.
--
-- Live turns were once deliberately NOT stored, because a row per turn would have meant a database write
-- on the chat path. That reason went when evaluation moved to its own application behind Kafka: the write
-- now happens in ragr-eval, off the chat path entirely. What storing them buys is everything a counter
-- cannot hold - which turn failed and why, the joint outcome of retrieval and generation on the SAME turn
-- (the debugging matrix), a queue the judges can work through at their own pace, and rows a human can
-- review. Prometheus still carries the rates; these tables carry the evidence.
--
-- Unlike document_metadata_history, whose missing foreign key is deliberate so that an audit trail
-- survives the deletion of its document, a case result has no meaning without its run. The foreign key
-- here is intentional and cascades. eval_feedback has none, deliberately: a rating can arrive before
-- the turn it rates has been stored.

CREATE TABLE IF NOT EXISTS eval_run
(
    id                   UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    suite                VARCHAR(100)                     NOT NULL,
    status               VARCHAR(50)                      NOT NULL,
    started_at           TIMESTAMP WITH TIME ZONE         NOT NULL,
    finished_at          TIMESTAMP WITH TIME ZONE,
    case_count           INT                              NOT NULL DEFAULT 0,
    passed_count         INT                              NOT NULL DEFAULT 0,

    -- Everything below describes the configuration under test. A score is not comparable to an earlier
    -- score unless these match, and recording them is what turns a list of runs into a regression
    -- history.
    chat_model          VARCHAR(100),
    judge_model          VARCHAR(100),
    judged               BOOLEAN                          NOT NULL DEFAULT FALSE,
    top_k                INT,
    similarity_threshold DOUBLE PRECISION,

    -- Aggregates, denormalised from eval_case_result so a trend query does not have to re-aggregate
    -- every case on every dashboard refresh.
    hit_rate             DOUBLE PRECISION,
    mean_reciprocal_rank DOUBLE PRECISION,

    -- Context precision, in both of its forms. The pair is deliberate and they are not redundant:
    -- *_precision is RAGAS's rank-weighted average, normalised by the relevant chunks FOUND, which
    -- measures ordering; precision_at_k is the plain relevant/k, which measures how much of the
    -- retrieved context was noise. A high average with a low precision@k is a well-ordered context
    -- that is mostly padding, and one number alone cannot say that.
    --
    -- The judged_* pair is the same two metrics with relevance decided by an LLM judge per chunk
    -- rather than by the dataset's expected pages. Nullable and normally null: judging is off by
    -- default because precision costs top-k judge calls per case rather than one. Null here means
    -- "not measured" and must never be averaged as a zero.
    --
    -- The cited_* pair counts a chunk as used when the answer cites its page: free, deterministic and
    -- measured on every run, the steadier cross-check on the judge. Null on runs from before it existed.
    context_precision        DOUBLE PRECISION,
    precision_at_k           DOUBLE PRECISION,
    judged_context_precision DOUBLE PRECISION,
    judged_precision_at_k    DOUBLE PRECISION,
    cited_context_precision  DOUBLE PRECISION,
    cited_precision_at_k     DOUBLE PRECISION,

    -- Page-level recall against expected pages (honouring each case's expectedPagesMode), and NDCG with
    -- expected-page chunks as the relevant ones, ranked over the whole candidate pool. Averages over the
    -- cases that declare expected pages.
    recall_at_k              DOUBLE PRECISION,
    ndcg_at_k                DOUBLE PRECISION,

    citation_validity    DOUBLE PRECISION,
    citation_fabrication DOUBLE PRECISION,
    relevancy_rate       DOUBLE PRECISION,
    groundedness_rate    DOUBLE PRECISION,
    phrase_coverage      DOUBLE PRECISION,
    duration_millis      BIGINT,
    error_message        TEXT
);

CREATE TABLE IF NOT EXISTS eval_case_result
(
    id                    UUID PRIMARY KEY   DEFAULT gen_random_uuid(),
    run_id                UUID                     NOT NULL REFERENCES eval_run (id) ON DELETE CASCADE,
    case_id               VARCHAR(200)             NOT NULL,
    query                 TEXT                     NOT NULL,
    answer                TEXT,

    -- Retrieval
    retrieved_count       INT                      NOT NULL DEFAULT 0,
    top_score             DOUBLE PRECISION,
    score_spread          DOUBLE PRECISION,
    pages_retrieved       TEXT,
    first_relevant_rank   INT                      NOT NULL DEFAULT 0,

    -- judged_relevance is the judge's per-chunk verdict vector in rank order, "1,0,1,1,0", read
    -- against pages_retrieved which is stored the same way. It is here because it is the only column
    -- in this table that cannot be reconstructed afterwards from the dataset and the other columns,
    -- and because the comparison it enables is the point of running both precisions: a chunk the judge
    -- called useful from a page expectedPages omits means the DATASET is too narrow, not that
    -- retrieval erred. cited_relevance is the same vector from the answer's own citations; where it
    -- and judged_relevance disagree, the judge is the likelier one wrong.
    context_precision        DOUBLE PRECISION,
    precision_at_k           DOUBLE PRECISION,
    judged_context_precision DOUBLE PRECISION,
    judged_precision_at_k    DOUBLE PRECISION,
    judged_relevance         TEXT,
    cited_context_precision  DOUBLE PRECISION,
    cited_precision_at_k     DOUBLE PRECISION,
    cited_relevance          TEXT,

    -- Reference metrics over the candidate pool. reference_relevance is the expected-page vector in pool
    -- rank order, "1,0,0,0,1,0,0,1,0,0", whose first retrieved_count entries were in the prompt - so
    -- it lines up with eval_turn_chunk.judge_grade for the judge-versus-reference comparison.
    recall_at_k              DOUBLE PRECISION,
    ndcg_at_k                DOUBLE PRECISION,
    reference_relevance      TEXT,

    -- The case's turn in eval_turn, which holds the judged metrics. Null when the run did not persist.
    turn_id                  UUID,

    -- Citations
    citations_emitted     INT                      NOT NULL DEFAULT 0,
    citations_valid       INT                      NOT NULL DEFAULT 0,
    citations_fabricated  INT                      NOT NULL DEFAULT 0,

    -- Answer
    phrase_coverage       DOUBLE PRECISION,
    refused               BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- Nullable on purpose: NULL means "not judged", which is different from FALSE meaning "judged and
    -- failed". A run with judging switched off, or a judgement dropped under load, must not read as a
    -- failure. Any aggregate over these columns has to exclude NULLs rather than coalesce them.
    relevancy_pass        BOOLEAN,
    groundedness_pass     BOOLEAN,

    passed                BOOLEAN                  NOT NULL DEFAULT FALSE,
    failure_reasons       TEXT,
    latency_millis        BIGINT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL
);

-- The dashboard's access patterns: the most recent run of a suite, the most recent COMPLETED run of
-- any suite, and every case belonging to a run. Without the last, the per-case table panel
-- sequentially scans the whole history.
--
-- The status index is not a micro-optimisation. EvalMetricsService reads the newest COMPLETED run
-- from the Prometheus scrape thread roughly every 30s for as long as the application is up, and that
-- query carries no suite predicate, so the suite index cannot serve it.
CREATE INDEX IF NOT EXISTS eval_run_suite_started_idx ON eval_run (suite, started_at DESC);
CREATE INDEX IF NOT EXISTS eval_run_status_started_idx ON eval_run (status, started_at DESC);
CREATE INDEX IF NOT EXISTS eval_case_result_run_idx ON eval_case_result (run_id);

-- Finding which cases regress across runs means filtering by case_id over time, which neither index
-- above serves.
CREATE INDEX IF NOT EXISTS eval_case_result_case_idx ON eval_case_result (case_id, created_at DESC);

-- One row per chat turn ragr-eval receives: live turns from the Kafka listener, golden turns from the
-- suite (with run_id and case_id). Written with judge_status PENDING and its deterministic scores; the
-- judge worker fills the judged columns later, when chat is idle - so occurred_at, not judged_at, is the
-- time axis for every quality panel.
--
-- Every judged column is nullable, and NULL means "not measured": the turn was ungrounded (only the task
-- is classified), the stage timed out, or the turn has not been judged yet. Aggregates must exclude
-- NULLs, never coalesce them to zero.
CREATE TABLE IF NOT EXISTS eval_turn
(
    turn_id               UUID PRIMARY KEY,
    origin                VARCHAR(20)              NOT NULL,
    run_id                UUID REFERENCES eval_run (id) ON DELETE CASCADE,
    case_id               VARCHAR(200),
    conversation_id       VARCHAR(200)             NOT NULL,
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    query                 TEXT                     NOT NULL,
    answer                TEXT,

    -- What answered, so a metric can be split at a change of any of them.
    chat_model            VARCHAR(100),
    prompt_version        VARCHAR(32),
    pipeline_version      VARCHAR(100),
    top_k                 INT,
    similarity_threshold  DOUBLE PRECISION,
    pool_size             INT,

    retrieval_millis      BIGINT,
    first_token_millis    BIGINT,
    total_millis          BIGINT,
    streamed              BOOLEAN,
    prompt_tokens         INT,
    completion_tokens     INT,
    finish_reason         VARCHAR(50),

    -- Deterministic, scored on arrival.
    grounded              BOOLEAN                  NOT NULL,
    retrieved_count       INT                      NOT NULL,
    pool_count            INT                      NOT NULL,
    top_score             DOUBLE PRECISION,
    score_spread          DOUBLE PRECISION,
    citations_emitted     INT                      NOT NULL DEFAULT 0,
    citations_valid       INT                      NOT NULL DEFAULT 0,
    citations_fabricated  INT                      NOT NULL DEFAULT 0,
    citations_repaired    INT                      NOT NULL DEFAULT 0,
    citations_abstained   INT                      NOT NULL DEFAULT 0,
    cited_context_precision DOUBLE PRECISION,
    cited_precision_at_k  DOUBLE PRECISION,
    refused               BOOLEAN                  NOT NULL DEFAULT FALSE,
    echoed_instruction    BOOLEAN                  NOT NULL DEFAULT FALSE,
    answer_chars          INT                      NOT NULL DEFAULT 0,
    -- Set when the next turn in the same conversation, soon after, retrieved largely the same chunks:
    -- the user asked again, the implicit form of a thumbs-down.
    rephrased             BOOLEAN                  NOT NULL DEFAULT FALSE,

    -- The judge queue. PENDING -> RUNNING -> DONE | PARTIAL; SKIPPED when the backlog outgrew
    -- app.eval.judge.max-backlog-age before the worker reached it.
    judge_status          VARCHAR(20)              NOT NULL,
    judge_started_at      TIMESTAMP WITH TIME ZONE,
    judged_at             TIMESTAMP WITH TIME ZONE,
    judge_millis          BIGINT,
    judge_model           VARCHAR(100),

    task_type             VARCHAR(40),

    -- Retrieval, from the judge's 0/1/2 grade of every chunk in the pool (eval_turn_chunk.judge_grade).
    -- A chunk is relevant at grade 2; NDCG uses the grades as gains. recall_at_k is POOLED recall -
    -- relevant chunks in the prompt over relevant chunks in the pool - and is NULL when the pool holds
    -- none, which relevant_in_pool = 0 records separately.
    precision_at_k        DOUBLE PRECISION,
    recall_at_k           DOUBLE PRECISION,
    mrr                   DOUBLE PRECISION,
    ndcg_at_k             DOUBLE PRECISION,
    relevant_in_context   INT,
    relevant_in_pool      INT,
    relevant_cut_off      INT,

    -- Generation
    relevancy_pass        BOOLEAN,
    groundedness_pass     BOOLEAN,
    claims_total          INT,
    claims_supported      INT,
    faithfulness          DOUBLE PRECISION,
    citations_checked     INT,
    citations_supported   INT,
    completeness_pass     BOOLEAN,

    -- End to end: retrieval_ok is a relevant chunk in the prompt; answer_ok is relevant, faithful,
    -- complete and citing nothing fabricated - the end-to-end success. Together they place the turn in
    -- the debugging matrix.
    retrieval_ok          BOOLEAN,
    answer_ok             BOOLEAN,

    -- Human review. review_sample marks the random share drawn into the review queue on arrival.
    review_sample         BOOLEAN                  NOT NULL DEFAULT FALSE,
    human_verdict         VARCHAR(20),
    human_notes           TEXT,
    reviewed_at           TIMESTAMP WITH TIME ZONE
);

-- The judge worker's claim: oldest PENDING first. Partial, so it stays small however much history there is.
CREATE INDEX IF NOT EXISTS eval_turn_pending_idx ON eval_turn (occurred_at) WHERE judge_status = 'PENDING';
-- Every live panel filters by origin over a time range.
CREATE INDEX IF NOT EXISTS eval_turn_origin_occurred_idx ON eval_turn (origin, occurred_at DESC);
-- Rephrase detection looks up the previous turn of a conversation.
CREATE INDEX IF NOT EXISTS eval_turn_conversation_idx ON eval_turn (conversation_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS eval_turn_run_idx ON eval_turn (run_id) WHERE run_id IS NOT NULL;

-- The whole candidate pool of a turn, in rank order: the first retrieved_count rows were in the prompt.
-- The text is kept because a reviewer cannot judge a verdict without reading what was judged.
CREATE TABLE IF NOT EXISTS eval_turn_chunk
(
    turn_id               UUID                     NOT NULL REFERENCES eval_turn (turn_id) ON DELETE CASCADE,
    rank                  INT                      NOT NULL,
    chunk_id              VARCHAR(100),
    document_id           VARCHAR(100),
    file_name             TEXT,
    page                  INT,
    section               TEXT,
    pipeline_version      VARCHAR(32),
    score                 DOUBLE PRECISION,
    in_context            BOOLEAN                  NOT NULL,
    cited                 BOOLEAN                  NOT NULL DEFAULT FALSE,
    judge_grade           SMALLINT,
    text                  TEXT,
    PRIMARY KEY (turn_id, rank)
);

-- Users' ratings. No foreign key to eval_turn on purpose: feedback and its turn arrive on different
-- topics in either order, and a rating for a turn already purged is still a rating.
CREATE TABLE IF NOT EXISTS eval_feedback
(
    id                    UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    turn_id               UUID                     NOT NULL,
    rating                VARCHAR(10)              NOT NULL,
    reason                TEXT,
    submitted_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS eval_feedback_turn_idx ON eval_feedback (turn_id);
CREATE INDEX IF NOT EXISTS eval_feedback_submitted_idx ON eval_feedback (submitted_at DESC);
