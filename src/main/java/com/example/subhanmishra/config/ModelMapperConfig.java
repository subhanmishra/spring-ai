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

        // Custom mapping for DocumentMetadata to DocumentMetadataDto (Record)
        modelMapper.createTypeMap(DocumentMetadata.class, DocumentMetadataDto.class)
                .setConverter(context -> {
                    DocumentMetadata source = context.getSource();
                    // Assuming DocumentMetadataDto has a canonical constructor matching these fields
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