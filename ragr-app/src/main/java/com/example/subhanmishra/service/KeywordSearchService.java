package com.example.subhanmishra.service;

import com.example.subhanmishra.config.RagProperties;
import com.example.subhanmishra.entity.KeywordChunk;
import com.example.subhanmishra.repository.KeywordChunkRepository;
import com.pgvector.PGvector;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The exact-name half of hybrid search: finds chunks containing a name from the question, to place
 * beside the vector search's chunks.
 *
 * <p>A question with no name runs no query. A name in more than {@code max-matches} chunks is ignored as
 * too common. Matches are ranked by their rarest name, then by similarity to the question.
 */
@Service
public class KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

    /** Added to the metadata of a chunk that reached the prompt through this search, with value "keyword". */
    public static final String RETRIEVED_BY = "retrievedBy";

    /**
     * The whitelist of names: dotted names (packages, classes, property keys), {@code @Annotations}, and
     * camelCase or PascalCase words.
     */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z_][\\w-]*(?:\\.[A-Za-z_][\\w-]*)+"
            + "|@[A-Za-z]\\w+"
            + "|\\b\\w*[a-z0-9][A-Z]\\w*");

    /** Shorter matches are words, not names. */
    private static final int MIN_LENGTH = 4;

    /** Bounds the query. */
    private static final int MAX_TERMS = 8;

    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {
    };

    private final KeywordChunkRepository repository;
    private final EmbeddingModel embeddingModel;
    private final JsonMapper jsonMapper;
    private final RagProperties.Keyword properties;

    public KeywordSearchService(KeywordChunkRepository repository, EmbeddingModel embeddingModel,
                                JsonMapper jsonMapper, RagProperties ragProperties) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.jsonMapper = jsonMapper;
        this.properties = ragProperties.keyword();
    }

    /**
     * The chunks to add to the prompt for this question, best first, at most {@code slots}; empty when
     * keyword search is off, the question names nothing, or every name is too common.
     *
     * @param documentId restricts the search to one document, as the diagnostics endpoint can; null for all
     */
    public List<Document> search(String question, @Nullable String documentId) {
        if (!properties.enabled() || properties.slots() == 0) {
            return List.of();
        }
        List<String> terms = identifiers(question);
        if (terms.isEmpty()) {
            return List.of();
        }

        long started = System.nanoTime();
        Map<String, Integer> rarest = repository.findSpecificMatches(terms, properties.maxMatches(), documentId)
                                                .stream()
                                                .collect(Collectors.toMap(match -> match.id().toString(),
                                                                          KeywordChunkRepository.NameMatch::rarest));
        if (rarest.isEmpty()) {
            log.debug("Keyword search: names {}, none specific enough, in {}ms", terms,
                      (System.nanoTime() - started) / 1_000_000);
            return List.of();
        }

        String embedding = new PGvector(embeddingModel.embed(question)).getValue();
        List<Document> chunks = repository.findWithDistance(rarest.keySet().stream().map(UUID::fromString).toList(),
                                                            embedding)
                                          .stream()
                                          .map(this::toDocument)
                                          .toList();
        List<Document> ranked = rank(chunks, rarest, properties.slots());
        log.debug("Keyword search: names {} matched {} chunk(s), {} kept, in {}ms", terms, rarest.size(),
                  ranked.size(), (System.nanoTime() - started) / 1_000_000);
        return ranked;
    }

    /** The identifiers in a question, in order of appearance, without repeats; at most {@link #MAX_TERMS}. */
    static List<String> identifiers(@Nullable String question) {
        if (question == null) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(question);
        while (matcher.find() && terms.size() < MAX_TERMS) {
            // A trailing hyphen is punctuation ("spring.main.lazy-initialization-"), not part of the name.
            String term = matcher.group().replaceAll("-+$", "");
            if (term.length() >= MIN_LENGTH) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    /** Rarest name first, then by score. */
    static List<Document> rank(List<Document> chunks, Map<String, Integer> rarest, int slots) {
        return chunks.stream()
                     .sorted(Comparator.<Document>comparingInt(chunk -> rarest.getOrDefault(chunk.getId(), Integer.MAX_VALUE))
                                       .thenComparing(chunk -> chunk.getScore() != null ? chunk.getScore() : 0.0,
                                                      Comparator.reverseOrder()))
                     .limit(slots)
                     .toList();
    }

    /** Shaped as {@code PgVectorStore} shapes its results: the distance in metadata, the score 1 - distance. */
    private Document toDocument(KeywordChunk chunk) {
        Map<String, Object> metadata = new HashMap<>(jsonMapper.readValue(chunk.metadata(), METADATA));
        metadata.put(DocumentMetadata.DISTANCE.value(), (float) chunk.distance());
        metadata.put(RETRIEVED_BY, "keyword");
        return Document.builder()
                       .id(chunk.id().toString())
                       .text(chunk.content())
                       .metadata(metadata)
                       .score(1.0 - chunk.distance())
                       .build();
    }
}
