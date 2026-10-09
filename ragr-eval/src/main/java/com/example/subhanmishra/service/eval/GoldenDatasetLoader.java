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
 * <p>With Jackson 3, the version Spring Boot 4 manages; its YAML module is declared in {@code pom.xml}.
 */
public final class GoldenDatasetLoader {

    private final ResourceLoader resourceLoader;
    private final YAMLMapper yamlMapper;

    public GoldenDatasetLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.yamlMapper = YAMLMapper.builder()
                // An unknown key fails the load: a misspelled `expectedPages` would silently drop the
                // assertion, and the suite would pass while measuring nothing.
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
