# ragr-shared

A plain library, not a Spring Boot application, holding the contracts between the three applications.
Nothing here runs on its own. Because the applications never call each other, these types are what
keeps them in agreement: a change here is a change to every application that uses it. For how the
applications fit together, see the [root README](../README.md).

| Package | Holds | Used by |
|---|---|---|
| `chunk` | `ChunkMetadata`: the metadata keys every stored chunk carries (`documentId`, `fileName`, `contentType`, `pageNumber`, `chunkIndex`, `blockType`, `tableIndex`, `tableRows`, `section`, `pipelineVersion`) | ragr-ingest writes them, ragr-app reads them, ragr-eval stores section and pipeline version with each evaluated turn |
| `citation` | `CitationParser` reads the `[filename, p. N]` line at the start of every chunk, strips it with the `Section:` line under it, and reads the `(filename, p. N)` citations in an answer; `CitationResolver` rewrites a section number cited as a page into that heading's page; `AnswerCitations` decides what counts as a citation, whether it is supported, and strips citations from the answer text | ragr-app to resolve and strip, ragr-eval to score - so the response and the fabrication rate cannot disagree; ragr-ingest to write the header and find section headings, so chunk boundaries and section resolution agree on what a heading is |
| `event` | `ChatTurnCompleted`, the Kafka event for one completed chat turn - the answer, the chunks in the prompt and the rest of the candidate pool, timings, token usage and the prompt version; `ChatFeedbackSubmitted`, a user's rating of one turn; and `TurnOrigin` (`LIVE` or `GOLDEN`) | ragr-app publishes, ragr-eval consumes |
| `exception` | `ApiExceptionHandler`, the shared `ProblemDetail` error handling, and `ResourceNotFoundException` | all three applications, each through its own `@RestControllerAdvice` subclass |

## Changing a contract

- **The citation line** - and the `Section:` line under it - is written by ragr-ingest
  (`DocumentIngestionService`), described to the model by ragr-app's prompts, and parsed here. Changing its format means changing all three, rebuilding
  every application, and re-ingesting every document, because the chunks already stored keep the old
  line.
- **`ChatTurnCompleted`** is serialised as JSON. Rebuild and restart both ragr-app and ragr-eval
  together. Fields are added as nullable boxes, never primitives, and `schemaVersion` says which shape an
  event has: Jackson 3 fails on a missing primitive, and a version-1 event (no pool, timings or usage) must
  still read. Turns already on the topic keep the old shape for up to its three-day retention, which
  matters if the consumer group's offsets are ever reset to replay them. `ChatFeedbackSubmitted` follows
  the same rules on `rag.chat.feedback`.
- **`ChunkMetadata` keys** are stored in each chunk's metadata column, so renaming one strands every
  chunk written under the old name: re-ingest.

`ApiExceptionHandler` is deliberately left unannotated. Its javadoc explains why, and each application
adds only the handlers it needs.

Web and servlet dependencies are `provided`: each application brings its own through Spring Boot.
