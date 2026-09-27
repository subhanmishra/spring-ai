# ragr-shared

A plain library, not a Spring Boot application, holding the contracts between the three applications.
Nothing here runs on its own. Because the applications never call each other, these types are what
keeps them in agreement: a change here is a change to every application that uses it. For how the
applications fit together, see the [root README](../README.md).

| Package | Holds | Used by |
|---|---|---|
| `chunk` | `ChunkMetadata`: the metadata keys every stored chunk carries (`documentId`, `fileName`, `contentType`, `pageNumber`, `chunkIndex`, `blockType`, `tableIndex`, `tableRows`) | ragr-ingest writes them, ragr-app reads them |
| `citation` | `CitationParser` reads the `[filename, p. N]` line at the start of every chunk and the `(filename, p. N)` citations in an answer; `CitationResolver` rewrites a section number cited as a page into that heading's page; `AnswerCitations` decides what counts as a citation, whether it is supported, and strips citations from the answer text | ragr-app to resolve and strip, ragr-eval to score - so the response and the fabrication rate cannot disagree |
| `event` | `ChatTurnCompleted`, the Kafka event for one completed chat turn, and `TurnOrigin` (`LIVE` or `GOLDEN`) | ragr-app publishes, ragr-eval consumes |
| `exception` | `ApiExceptionHandler`, the shared `ProblemDetail` error handling, and `ResourceNotFoundException` | ragr-app and ragr-ingest, each through its own `@RestControllerAdvice` subclass |

## Changing a contract

- **The citation line** is written by ragr-ingest (`DocumentIngestionService`), described to the model
  by ragr-app's prompts, and parsed here. Changing its format means changing all three, rebuilding
  every application, and re-ingesting every document, because the chunks already stored keep the old
  line.
- **`ChatTurnCompleted`** is serialised as JSON. Rebuild and restart both ragr-app and ragr-eval
  together. Turns already on the topic keep the old shape for up to its three-day retention, which
  matters if the consumer group's offsets are ever reset to replay them.
- **`ChunkMetadata` keys** are stored in each chunk's metadata column, so renaming one strands every
  chunk written under the old name: re-ingest.

`ApiExceptionHandler` is deliberately left unannotated. Its javadoc explains why, and each application
adds only the handlers it needs.

Web and servlet dependencies are `provided`: each application brings its own through Spring Boot.
