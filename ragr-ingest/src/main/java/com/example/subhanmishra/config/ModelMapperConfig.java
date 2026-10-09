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

        // DocumentMetadata to DocumentMetadataDto, by hand through the record's constructor - NOT by field
        // name. A field added to the record is null until it is added here too, with no error anywhere.
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
