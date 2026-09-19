-- V5__Add_Eval_Results.sql

-- Store the results of curated ("golden") evaluation runs, so pipeline quality can be compared over
-- time rather than only observed in the moment.
--
-- Live chat traffic is NOT stored here. Online evaluation emits Micrometer metrics and nothing else:
-- a per-request time series belongs in Prometheus, which already scrapes this application, and writing
-- a row per chat turn would put a database write on the chat path to record a metric that Prometheus
-- stores better. These tables hold discrete, comparable runs of a fixed dataset - the thing you want
-- to diff across a pipeline change - and that is a different shape of data with a different lifetime.
--
-- Unlike document_metadata_history, whose missing foreign key is deliberate so that an audit trail
-- survives the deletion of its document, a case result has no meaning without its run. The foreign key
-- here is intentional and cascades.

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
    -- history. pipeline_version in particular ties a score to the ingestion generation that produced
    -- the chunks being retrieved: a corpus re-ingested under a new PipelineProvenance.CURRENT_VERSION
    -- is a different corpus, and comparing across that boundary silently compares two different things.
    chat_model           VARCHAR(100),
    judge_model          VARCHAR(100),
    judged               BOOLEAN                          NOT NULL DEFAULT FALSE,
    top_k                INT,
    similarity_threshold DOUBLE PRECISION,
    pipeline_version     INT,

    -- Aggregates, denormalised from eval_case_result so a trend query does not have to re-aggregate
    -- every case on every dashboard refresh.
    hit_rate             DOUBLE PRECISION,
    mean_reciprocal_rank DOUBLE PRECISION,
    citation_validity    DOUBLE PRECISION,
    citation_fabrication DOUBLE PRECISION,
    relevancy_rate       DOUBLE PRECISION,
    groundedness_rate    DOUBLE PRECISION,
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

-- The dashboard's two access patterns: the most recent run of a suite, and every case belonging to a
-- run. Without the second, the per-case table panel sequentially scans the whole history.
CREATE INDEX IF NOT EXISTS eval_run_suite_started_idx ON eval_run (suite, started_at DESC);
CREATE INDEX IF NOT EXISTS eval_case_result_run_idx ON eval_case_result (run_id);

-- Finding which cases regress across runs means filtering by case_id over time, which neither index
-- above serves.
CREATE INDEX IF NOT EXISTS eval_case_result_case_idx ON eval_case_result (case_id, created_at DESC);
