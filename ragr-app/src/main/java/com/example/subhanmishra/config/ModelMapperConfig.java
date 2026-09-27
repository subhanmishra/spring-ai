package com.example.subhanmishra.config;

import com.example.subhanmishra.dto.DocumentMetadataDto;
import com.example.subhanmishra.entity.DocumentMetadata;
import org.modelmapper.ModelMapper;
import org.modelmapper.convention.MatchingStrategies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelMapperConfig {

    @Bean
    public ModelMapper modelMapper() {
        ModelMapper modelMapper = new ModelMapper();
        modelMapper.getConfiguration()
                .setMatchingStrategy(MatchingStrategies.STRICT);

        // Custom mapping for DocumentMetadata to DocumentMetadataDto (Record).
        //
        // Note this is a hand-written converter calling the canonical constructor, NOT a field-name
        // mapping - so a component added to the record does not flow through on its own. Forgetting to
        // extend this returns null for the new field with no error anywhere, which is the easiest way
        // to ship a half-working change here. Add to both sides together.
        modelMapper.createTypeMap(DocumentMetadata.class, DocumentMetadataDto.class)
                .setConverter(context -> {
                    DocumentMetadata source = context.getSource();
                    return new DocumentMetadataDto(
                            source.getId(),
                            source.getFilename(),
                            source.getContentType(),
                            source.getFileSize(),
                            source.getTotalPages(),
                            source.getTotalChunks(),
                            source.getStatus(),
                            source.getErrorMessage(),
                            source.getCreatedAt(),
                            source.getUpdatedAt()
                    );
                });

        return modelMapper;
    }
}
