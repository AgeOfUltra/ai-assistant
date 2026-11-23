package com.ai.assistant.aiassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
        "spring.ai.openai.api-key=test-key",
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.ai.vectorstore.pgvector.initialize-schema=false",
        "rag.data-dir=/tmp/nonexistent"
    }
)
class AiAssistantApplicationTests {

	@Test
	void contextLoads() {
	}

}
