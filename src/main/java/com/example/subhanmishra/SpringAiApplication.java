package com.example.subhanmishra;

import com.example.subhanmishra.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RagProperties.class)
public class SpringAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiApplication.class, args);
    }

//    @Bean
//    public ApplicationRunner runner(VectorStore vectorStore) {
//        return args -> {
//            List<Document> documents = List.of(
//                    new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!", Map.of("meta1", "meta1")),
//                    new Document("The World is Big and Salvation Lurks Around the Corner"),
//                    new Document("You walk forward facing the past and you turn back toward the future.", Map.of("meta2", "meta2")));
//
//            // Add the documents to PGVector
//            vectorStore.add(documents);
//
//            // Retrieve documents similar to a query
//            List<Document> results = vectorStore.similaritySearch(SearchRequest.builder().query("Spring").topK(5).build());
//            IO.println("----Vector similarity result for Spring----" + results);
//        };
//    }

}
