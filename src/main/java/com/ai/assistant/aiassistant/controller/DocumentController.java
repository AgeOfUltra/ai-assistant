package com.ai.assistant.aiassistant.controller;

import com.ai.assistant.aiassistant.service.DocumentIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentIngestionService documentIngestionService;

    public DocumentController(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest() {
        log.info("Manual ingestion triggered");
        documentIngestionService.ingestAll();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Document ingestion completed"
        ));
    }

    @DeleteMapping("/documents")
    public ResponseEntity<Map<String, String>> clearDocuments() {
        log.info("Clearing all documents");
        documentIngestionService.clearAll();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "All documents cleared from vector store"
        ));
    }
}
