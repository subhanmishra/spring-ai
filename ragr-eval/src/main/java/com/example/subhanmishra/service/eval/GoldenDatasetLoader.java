package com.example.subhanmishra.service.eval;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a {@link GoldenDataset} from a YAML resource.
 *
 * <p>Jackson 3, the version Spring Boot 4 manages, with its YAML module declared in {@code pom.xml}. In
 * {@code ragr-app} this class used Jackson 2 because a Jackson 2 YAML module happened to arrive there
 * through springdoc; nothing here brings one, and taking a second major version of Jackson onto the
 * classpath deliberately to keep that would be the wrong way round.
 */
public final class GoldenDatasetLoader {

    private final ResourceLoader resourceLoader;
    private final YAMLMapper yamlMapper;

    public GoldenDatasetLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.yamlMapper = YAMLMapper.builder()
                // A typo in a case's key is a mistake in the dataset, not something to silently ignore -
                // a misspelled `expectedPages` would turn a recall assertion into no assertion at all,
                // and the suite would pass while measuring nothing.
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
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
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to read golden eval dataset at " + location, e);
        }
    }
}
