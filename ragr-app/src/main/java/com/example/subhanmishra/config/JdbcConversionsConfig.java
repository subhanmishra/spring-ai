package com.example.subhanmishra.config;

import com.example.subhanmishra.service.provenance.PipelineSettings;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.sql.SQLException;
import java.util.List;

/**
 * Maps {@link PipelineSettings} to and from the {@code document_metadata.pipeline_settings} JSONB
 * column.
 *
 * <p>Converters are registered for that one concrete type rather than for {@code Map<String, Object>}:
 * a converter on so general a type would apply to every map-valued property Spring Data JDBC ever
 * maps, in this application and in Spring AI's own entities, which is a much wider blast radius than
 * this needs.
 *
 * <p>The write side must produce a {@link PGobject} typed {@code jsonb}. Handing the driver a plain
 * {@code String} sends it as {@code varchar}, and Postgres rejects that against a {@code jsonb}
 * column rather than coercing it.
 */
@Configuration
public class JdbcConversionsConfig {

    /**
     * A mapper of its own rather than the context's. Spring Boot 4 ships both Jackson 2
     * ({@code com.fasterxml.jackson}) and Jackson 3 ({@code tools.jackson}) and publishes a bean only
     * for the latter, so injecting "the" {@code ObjectMapper} fails to resolve. Nothing here needs the
     * application's serialisation settings anyway - this writes one small fixed record - and a local
     * mapper keeps the stored format from shifting if those settings are ever retuned.
     *
     * <p>Unknown properties are ignored on read so that a snapshot written by a later version, with
     * settings this build does not know about, still deserialises instead of failing the whole listing.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Bean
    public JdbcCustomConversions jdbcCustomConversions() {
        return new JdbcCustomConversions(List.of(new PipelineSettingsWritingConverter(MAPPER),
                                                 new PipelineSettingsReadingConverter(MAPPER)));
    }

    @WritingConverter
    static class PipelineSettingsWritingConverter implements Converter<PipelineSettings, PGobject> {

        private final ObjectMapper objectMapper;

        PipelineSettingsWritingConverter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public PGobject convert(PipelineSettings source) {
            try {
                PGobject json = new PGobject();
                json.setType("jsonb");
                json.setValue(objectMapper.writeValueAsString(source));
                return json;
            }
            catch (SQLException | com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("Could not serialise pipeline settings", e);
            }
        }
    }

    @ReadingConverter
    static class PipelineSettingsReadingConverter implements Converter<PGobject, PipelineSettings> {

        private final ObjectMapper objectMapper;

        PipelineSettingsReadingConverter(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public PipelineSettings convert(PGobject source) {
            String value = source.getValue();
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return objectMapper.readValue(value, PipelineSettings.class);
            }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // A row written by an older or hand-edited schema should not break the whole listing;
                // an unreadable snapshot reads as absent, which reports the document as stale.
                return null;
            }
        }
    }
}
