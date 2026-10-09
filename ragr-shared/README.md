# ragr-shared

A plain Java library - not a Spring Boot application - holding the contracts between the three
applications. Nothing here runs on its own.

The applications never call each other, so these types are what keeps them in agreement. **A change
here is a change to every application that uses it.** How the applications fit together is in the
[root README](../README.md).

| Package | Holds | Used by |
|---|---|---|
| `chunk` | `ChunkMetadata`: the metadata keys every stored chunk carries (`documentId`, `fileName`, `contentType`, `pageNumber`, `chunkIndex`, `blockType`, `tableIndex`, `tableRows`, `section`, `pipelineVersion`) | ragr-ingest writes them; ragr-app reads them; ragr-eval stores `section` and `pipelineVersion` with each evaluated turn |
| `citation` | `CitationParser` reads the `[filename, p. N]` line at the start of a chunk, removes it with the `Section:` line under it, and reads `(filename, p. N)` citations in an answer. `CitationResolver` turns a section number cited as a page into that heading's page. `AnswerCitations` decides what counts as a citation and whether it is supported, and removes citations from the answer text | ragr-app to fix and remove citations, ragr-eval to score them - one implementation, so the response and the fabrication rate cannot disagree. ragr-ingest to write the header and find section headings, so chunk boundaries and citation fixing agree on what a heading is |
| `embedding` | `TaskPrefixEmbeddingModel`: wraps the embedding model so every text sent to it starts with the model's task prefix (`app.embedding.task-prefix`). Only the request changes, never the stored text | ragr-ingest with the passage prefix, ragr-app with the query prefix |
| `event` | The Kafka messages: `ChatTurnCompleted` (one finished answer - the chunks in the prompt and the rest of the pool, timings, token usage, the prompt version), `ChatFeedbackSubmitted` (a user's rating of one turn), and `TurnOrigin` (`LIVE` or `GOLDEN`) | ragr-app publishes, ragr-eval consumes |
| `exception` | `ApiExceptionHandler`, the shared `ProblemDetail` error handling, and `ResourceNotFoundException` | all three, each through its own `@RestControllerAdvice` subclass |

## Changing a contract

| Contract | What else has to change |
|---|---|
| **The citation line** and the `Section:` line under it | Written by ragr-ingest (`DocumentIngestionService`), explained to the model by ragr-app's prompts, read here. Change all three, rebuild every application, and re-ingest every document, since stored chunks keep the old line |
| **`ChatTurnCompleted`**, `ChatFeedbackSubmitted` | Rebuild and restart ragr-app and ragr-eval together. See the rules below |
| **The task prefixes** | They are a pair, set in ragr-ingest and ragr-app, and each model has its own. Change both together, with the model, and re-ingest, since every stored vector was made with the old passage prefix |
| **A `ChunkMetadata` key** | Stored in each chunk's metadata, so renaming one strands every chunk written under the old name. Re-ingest |

**Rules for the Kafka events.** They are sent as JSON and must still read when an older event arrives:

- Add fields as nullable boxed types (`Integer`, not `int`). Jackson 3 fails on a missing primitive.
- `schemaVersion` says which shape an event has. A version-1 event, with no pool, timings or usage,
  must still read.
- Events stay on the topic for its 3-day retention, so the old shape can come back if the consumer
  group's offsets are ever reset to replay them.

`ApiExceptionHandler` is deliberately not annotated as an advice; its javadoc says why. Each application
adds only the handlers it needs.

Web and servlet dependencies are `provided`: each application brings its own through Spring Boot.
