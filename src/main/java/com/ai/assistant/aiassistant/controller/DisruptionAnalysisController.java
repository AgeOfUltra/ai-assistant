package com.ai.assistant.aiassistant.controller;

import com.ai.assistant.aiassistant.dto.ChatResponse;
import com.ai.assistant.aiassistant.dto.DisruptionAnalysisResponse;
import com.ai.assistant.aiassistant.service.DisruptionAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for structured disruption analysis queries.
 * Provides endpoints for the AI agent to query specific disruption data
 * and for the dashboard to display structured analysis results.
 */
@RestController
@RequestMapping("/api/disruption")
public class DisruptionAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(DisruptionAnalysisController.class);

    private final DisruptionAnalysisService analysisService;

    public DisruptionAnalysisController(DisruptionAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    /**
     * Get overall disruption summary statistics.
     * GET /api/disruption/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        log.info("Disruption summary requested");
        return ResponseEntity.ok(analysisService.getSummaryStatistics());
    }

    /**
     * Get top high-risk flights by delay probability.
     * GET /api/disruption/high-risk?topN=10
     */
    @GetMapping("/high-risk")
    public ResponseEntity<List<Map<String, Object>>> getHighRiskFlights(
            @RequestParam(defaultValue = "10") int topN) {
        log.info("High risk flights requested (top {})", topN);
        return ResponseEntity.ok(analysisService.getHighRiskFlights(topN));
    }

    /**
     * Get flights with highest probability of severe delay (>60 min).
     * GET /api/disruption/severe-delay?topN=10
     */
    @GetMapping("/severe-delay")
    public ResponseEntity<List<Map<String, Object>>> getSevereDelayFlights(
            @RequestParam(defaultValue = "10") int topN) {
        log.info("Severe delay flights requested (top {})", topN);
        return ResponseEntity.ok(analysisService.getSevereDelayFlights(topN));
    }

    /**
     * Get flights with highest cascade risk (downstream impact).
     * GET /api/disruption/cascade-risk?topN=10
     */
    @GetMapping("/cascade-risk")
    public ResponseEntity<List<Map<String, Object>>> getHighCascadeRiskFlights(
            @RequestParam(defaultValue = "10") int topN) {
        log.info("High cascade risk flights requested (top {})", topN);
        return ResponseEntity.ok(analysisService.getHighCascadeRiskFlights(topN));
    }

    /**
     * Get flights with highest passenger priority scores.
     * GET /api/disruption/passenger-priority?topN=10
     */
    @GetMapping("/passenger-priority")
    public ResponseEntity<List<Map<String, Object>>> getHighPriorityPassengerFlights(
            @RequestParam(defaultValue = "10") int topN) {
        log.info("High priority passenger flights requested (top {})", topN);
        return ResponseEntity.ok(analysisService.getHighPriorityPassengerFlights(topN));
    }

    /**
     * Get misconnection risks above a threshold.
     * GET /api/disruption/misconnection?threshold=0.5&topN=10
     */
    @GetMapping("/misconnection")
    public ResponseEntity<List<Map<String, Object>>> getHighMisconnectionRisks(
            @RequestParam(defaultValue = "0.5") double threshold,
            @RequestParam(defaultValue = "10") int topN) {
        log.info("High misconnection risks requested (threshold: {}, top: {})", threshold, topN);
        return ResponseEntity.ok(analysisService.getHighMisconnectionRisks(threshold, topN));
    }

    /**
     * Get recovery recommendations for a specific disrupted flight.
     * GET /api/disruption/recovery?flightNumber=G9406
     */
    @GetMapping("/recovery")
    public ResponseEntity<List<Map<String, Object>>> getRecoveryOptions(
            @RequestParam String flightNumber) {
        log.info("Recovery options requested for flight {}", flightNumber);
        return ResponseEntity.ok(analysisService.getRecoveryOptions(flightNumber));
    }

    /**
     * Get flights for a specific date.
     * GET /api/disruption/flights-by-date?date=2026-11-21
     */
    @GetMapping("/flights-by-date")
    public ResponseEntity<List<Map<String, Object>>> getFlightsByDate(
            @RequestParam String date) {
        log.info("Flights by date requested: {}", date);
        return ResponseEntity.ok(analysisService.getFlightsByDate(date));
    }

    /**
     * Get flights for a specific route.
     * GET /api/disruption/flights-by-route?dep=SHJ&arr=DEL
     */
    @GetMapping("/flights-by-route")
    public ResponseEntity<List<Map<String, Object>>> getFlightsByRoute(
            @RequestParam String dep,
            @RequestParam String arr) {
        log.info("Flights by route requested: {} → {}", dep, arr);
        return ResponseEntity.ok(analysisService.getFlightsByRoute(dep, arr));
    }

    /**
     * Get flights for a specific aircraft registration.
     * GET /api/disruption/flights-by-registration?reg=A6-ARG
     */
    @GetMapping("/flights-by-registration")
    public ResponseEntity<List<Map<String, Object>>> getFlightsByRegistration(
            @RequestParam String reg) {
        log.info("Flights by registration requested: {}", reg);
        return ResponseEntity.ok(analysisService.getFlightsByRegistration(reg));
    }

    /**
     * Comprehensive disruption analysis for a natural language query.
     * POST /api/disruption/analyze
     * Body: {"query": "What are the major disruptions expected today?"}
     */
    @PostMapping("/analyze")
    public ResponseEntity<DisruptionAnalysisResponse> analyzeDisruption(
            @RequestBody Map<String, String> request) {
        String query = request.getOrDefault("query", "");
        log.info("Disruption analysis requested: {}", query);
        return ResponseEntity.ok(analysisService.analyzeDisruption(query));
    }

    /**
     * Get data context for RAG enrichment.
     * POST /api/disruption/context
     * Body: {"query": "What disruptions will affect SHJ hub?"}
     */
    @PostMapping("/context")
    public ResponseEntity<Map<String, String>> getDataContext(
            @RequestBody Map<String, String> request) {
        String query = request.getOrDefault("query", "");
        log.info("Data context requested for query: {}", query);
        String context = analysisService.getDataContextForRAG(query);
        return ResponseEntity.ok(Map.of("context", context));
    }

    /**
     * Force reload all cached data.
     * POST /api/disruption/reload
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadData() {
        log.info("Data reload requested");
        analysisService.reloadData();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Data cache cleared and will reload on next query"
        ));
    }
}
