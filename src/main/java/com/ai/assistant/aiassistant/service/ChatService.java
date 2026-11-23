package com.ai.assistant.aiassistant.service;

import com.ai.assistant.aiassistant.config.RAGProperties;
import com.ai.assistant.aiassistant.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
        You are Maya, a senior Airline Disruption Analyst with 15 years in Operations Control
        Centres (OCC). You are advising the duty manager of Air Arabia (G9) in real time.
        You are a trusted colleague, not a report generator.

        HOW YOU THINK
        - Start with the headline: what is the one thing the manager must know right now?
        - Prioritise by impact: rotation cascade first, then passengers, then revenue.
        - Weigh trade-offs like a human would: "Option A saves cost, but Option B protects
          the 18:40 rotation, which is worth more." Always give your pick and say why.
        - Think commercially: protect high-yield and connecting passengers, avoid EU261-style
          compensation or care costs where the data supports it, minimise aircraft idle time,
          and protect the downstream flights with the highest revenue exposure.

        HOW YOU TALK
        - Natural and conversational, like briefing a colleague in the ops room.
        - Use phrases like "My concern here is...", "I'd start with...", "The good news is...".
        - Be direct and confident but honest about uncertainty: "I'd treat this as likely, not certain."
        - No stiff openers ("Based on the provided context..."), no mention of "context",
          "the prompt" or "the data you gave me". Just refer to the data naturally
          ("rotation_risk_scores shows...").
        - Short paragraphs. Use a table only when comparing several flights. No walls of bullets.

        GROUNDING (non-negotiable)
        - Use ONLY numbers, flights and costs found in the CONTEXT sections below. Never invent
          flight numbers, probabilities, passenger counts or costs.
        - Cite the source file for key claims, e.g. (source: rotation_risk_scores_21-30Nov.csv).
        - If something is missing, say so plainly as an analyst would ("I don't have cost
          estimates for this route, so I can't rank those options financially") and carry on
          with what you do have. Don't ask which system or dashboard they use.
        - Revenue and cost suggestions must be framed as reasoned judgement built on the data,
          not as exact figures, unless the figures exist in the context.

        RESPONSE SHAPE
        1. The headline: 2 sentences, plain English.
        2. What's at risk: top flights or rotations, with the numbers that justify them.
        3. My recommendation: the best action first, then a backup, with the reasoning.
        4. Commercial angle: how to protect revenue and limit cost (rebooking priority,
           swaps, protecting connections, passenger comms).
        5. End with one natural follow-up offer or question, e.g. "Want me to map the
           knock-on effect on the evening rotation?"
        """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RAGProperties ragProperties;
    private final DisruptionAnalysisService disruptionAnalysisService;

    public ChatService(ChatClient chatClient, VectorStore vectorStore, RAGProperties ragProperties,
                       DisruptionAnalysisService disruptionAnalysisService) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.disruptionAnalysisService = disruptionAnalysisService;
    }

    /** Everything needed for one request: the system text (rules + data) and the source list for the UI. */
    private record Prepared(String systemText, List<ChatResponse.Source> sources) {}

    private Prepared prepare(String message) {
        List<Document> documents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(message)
                        .topK(ragProperties.getTopK())
                        .similarityThreshold(ragProperties.getSimilarityThreshold())
                        .build());

        String ragContext = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String analysisContext = disruptionAnalysisService.getDataContextForRAG(message);

        log.info("RAG docs: {}, analysis context chars: {}, rag context chars: {}",
                documents.size(), analysisContext.length(), ragContext.length());
        log.debug("Analysis context for '{}':\n{}", message, analysisContext);

        StringBuilder system = new StringBuilder(SYSTEM_PROMPT);
        system.append("\n=== CONTEXT: STRUCTURED ANALYSIS (computed from the CSV files) ===\n")
                .append(analysisContext.isBlank() ? "(none)" : analysisContext);
        system.append("\n=== CONTEXT: RETRIEVED DATA FILE SUMMARIES ===\n")
                .append(ragContext.isBlank() ? "(none retrieved)" : ragContext);

        List<ChatResponse.Source> sources = documents.stream()
                .map(doc -> new ChatResponse.Source(
                        String.valueOf(doc.getMetadata().getOrDefault("source_file", "unknown")),
                        doc.getText().substring(0, Math.min(doc.getText().length(), 300))))
                .collect(Collectors.toCollection(ArrayList::new));

        sources.add(new ChatResponse.Source(
                "DisruptionAnalysisService",
                "Structured analysis of rotation_risk_scores, passenger_priority_scores, misconnection_risk_scores, and recovery_recommendations"));

        return new Prepared(system.toString(), sources);
    }

    public ChatResponse chat(String message, String conversationId) {
        log.info("Chat request: {} (conversation: {})", message, conversationId);
        Prepared p = prepare(message);

        // Rules + data go in a system message; only the plain question goes in the user message,
        // so chat memory stores short questions and answers instead of the whole data dump every turn.
        String response = chatClient.prompt()
                .messages(new SystemMessage(p.systemText()))
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        log.info("Chat response generated with {} sources", p.sources().size());
        return new ChatResponse(response, p.sources());
    }

    public SseEmitter streamChat(String message, String conversationId) {
        SseEmitter emitter = new SseEmitter(60000L);

        try {
            Prepared p = prepare(message);

            chatClient.prompt()
                    .messages(new SystemMessage(p.systemText()))
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .subscribe(
                            chunk -> send(emitter, "message", chunk),
                            error -> {
                                log.error("Stream error", error);
                                send(emitter, "error", error.getMessage());
                                emitter.complete();
                            },
                            () -> {
                                send(emitter, "sources", new ChatResponse("", p.sources()));
                                emitter.complete();
                            });
        } catch (Exception e) {
            log.error("Error setting up stream", e);
            send(emitter, "error", e.getMessage());
            emitter.complete();
        }

        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            log.error("Error sending SSE '{}' event", event, e);
        }
    }
}