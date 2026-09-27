package com.example.subhanmishra.service.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Reads a {@link GoldenDataset} from a YAML resource.
 *
 * <p>Uses the Jackson 2 mapper explicitly rather than injecting one, for the same reason
 * {@code JdbcConversionsConfig} does: Spring Boot 4 ships both Jackson 2
 * ({@code com.fasterxml.jackson}) and Jackson 3 ({@code tools.jackson}) and publishes a bean only for
 * the latter, so injecting {@code ObjectMapper} would bind the wrong one. {@code jackson-dataformat-yaml}
 * is already on the compile classpath, so this needs no new dependency.
 */
public final class GoldenDatasetLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper yamlMapper;

    public GoldenDatasetLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                // A typo in a case's key is a mistake in the dataset, not something to silently ignore -
                // a misspelled `expectedPages` would turn a recall assertion into no assertion at all,
                // and the suite would pass while measuring nothing.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public GoldenDataset load(String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Golden eval dataset not found at " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            GoldenDataset dataset = yamlMapper.readValue(in, GoldenDataset.class);
            if (dataset.cases().isEmpty()) {
                throw new IllegalStateException("Golden eval dataset at " + location + " contains no cases");
            }
            return dataset;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read golden eval dataset at " + location, e);
        }
    }
}
