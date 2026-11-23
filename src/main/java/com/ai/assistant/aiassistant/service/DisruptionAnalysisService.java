package com.ai.assistant.aiassistant.service;

import com.ai.assistant.aiassistant.config.RAGProperties;
import com.ai.assistant.aiassistant.dto.DisruptionAnalysisResponse;
import com.ai.assistant.aiassistant.util.CsvUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the disruption CSV outputs and builds structured, pre-computed context for the AI agent.
 * Ranking and filtering happen here in code; the LLM only explains the short lists it is given.
 */
@Service
public class DisruptionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DisruptionAnalysisService.class);

    private static final Pattern FLIGHT_NO = Pattern.compile("\\bG9\\s*(\\d{3,4})\\b", Pattern.CASE_INSENSITIVE);

    // Prefix matching with a leading word boundary: "delay" matches "delays", "stop" does NOT match "top"
    private static final Pattern RISK_Q = Pattern.compile(
            "\\b(risk|cancel|delay|disrupt|worst|top|critical|issue|problem|major|concern|urgent|late)");
    private static final Pattern CASCADE_Q = Pattern.compile(
            "\\b(cascade|downstream|impact|affect|propagat|knock|rotation|backbone|domino)");
    private static final Pattern PASSENGER_Q = Pattern.compile(
            "\\b(passenger|priority|pax|recover|high.value|vip|elite)");
    private static final Pattern MISCONNECT_Q = Pattern.compile(
            "\\b(misconnect|connection|connecting|transfer|miss(ed|ing)?\\b)");
    private static final Pattern RECOVERY_Q = Pattern.compile(
            "\\b(recover|alternative|option(s)?\\b|rebook|reaccommodat|re-accommodat)");
    private static final Pattern AIRPORT_Q = Pattern.compile("\\b(airport|hub|station|shj)");

    private final RAGProperties ragProperties;

    private volatile List<Map<String, Object>> riskScores;
    private volatile List<Map<String, Object>> passengerScores;
    private volatile List<Map<String, Object>> misconnectionScores;
    private volatile List<Map<String, Object>> recoveryRecommendations;

    public DisruptionAnalysisService(RAGProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    // ------------------------------------------------------------------
    // Intent detection
    // ------------------------------------------------------------------

    private record Intent(boolean risk, boolean cascade, boolean passenger,
                          boolean misconnection, boolean recovery, boolean airport) {}

    private Intent detectIntent(String query) {
        String q = query == null ? "" : query.toLowerCase();
        boolean risk = RISK_Q.matcher(q).find();
        boolean cascade = CASCADE_Q.matcher(q).find();
        boolean passenger = PASSENGER_Q.matcher(q).find();
        boolean misconnection = MISCONNECT_Q.matcher(q).find();
        boolean recovery = RECOVERY_Q.matcher(q).find();
        boolean airport = AIRPORT_Q.matcher(q).find();

        // Nothing matched (e.g. "what are the major issues?"): give the model the full overview
        if (!(risk || cascade || passenger || misconnection || recovery || airport)) {
            risk = cascade = passenger = misconnection = true;
        }
        return new Intent(risk, cascade, passenger, misconnection, recovery, airport);
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    private List<Map<String, Object>> loadCsv(Path filePath) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return rows;

            String[] headers = CsvUtil.parseLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = CsvUtil.parseLine(line);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    String value = i < values.length ? values[i].trim() : "";
                    row.put(headers[i].trim(), parseValue(value));
                }
                rows.add(row);
            }
        } catch (IOException e) {
            log.error("Failed to read CSV: {}", filePath, e);
        }
        return rows;
    }

    private Object parseValue(String value) {
        if (value.isEmpty()) return value;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private List<Map<String, Object>> getRiskScores() {
        if (riskScores == null) {
            riskScores = loadCsv(Paths.get(ragProperties.getDataDir(), "rotation_risk_scores_21-30Nov.csv"));
            log.info("Loaded {} risk score records", riskScores.size());
        }
        return riskScores;
    }

    private List<Map<String, Object>> getPassengerScores() {
        if (passengerScores == null) {
            passengerScores = loadCsv(Paths.get(ragProperties.getDataDir(), "passenger_priority_scores.csv"));
            log.info("Loaded {} passenger priority records", passengerScores.size());
        }
        return passengerScores;
    }

    private List<Map<String, Object>> getMisconnectionScores() {
        if (misconnectionScores == null) {
            misconnectionScores = loadCsv(Paths.get(ragProperties.getDataDir(), "misconnection_risk_scores.csv"));
            log.info("Loaded {} misconnection risk records", misconnectionScores.size());
        }
        return misconnectionScores;
    }

    private List<Map<String, Object>> getRecoveryRecommendations() {
        if (recoveryRecommendations == null) {
            recoveryRecommendations = loadCsv(Paths.get(ragProperties.getDataDir(), "recovery_recommendations.csv"));
            log.info("Loaded {} recovery recommendation records", recoveryRecommendations.size());
        }
        return recoveryRecommendations;
    }

    // ------------------------------------------------------------------
    // Analysis methods
    // ------------------------------------------------------------------

    public List<Map<String, Object>> getHighRiskFlights(int topN) {
        return topBy(getRiskScores(), "p_delay_gt15", topN);
    }

    public List<Map<String, Object>> getSevereDelayFlights(int topN) {
        return topBy(getRiskScores(), "p_delay_gt60", topN);
    }

    public List<Map<String, Object>> getHighCascadeRiskFlights(int topN) {
        return topBy(getRiskScores(), "cascade_risk", topN);
    }

    public List<Map<String, Object>> getHighPriorityPassengerFlights(int topN) {
        return topBy(getPassengerScores(), "priority_score", topN);
    }

    private List<Map<String, Object>> topBy(List<Map<String, Object>> rows, String column, int topN) {
        return rows.stream()
                .filter(r -> r.get(column) instanceof Number)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> toDouble(r.get(column))).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    /**
     * "Critical" = likely to be late, likely to be very late, AND disruptive to the rest of the rotation.
     * Weights are a starting point; tune them with your operations team.
     */
    public List<Map<String, Object>> getCriticalFlights(int topN) {
        List<Map<String, Object>> scores = getRiskScores();
        double maxCascade = scores.stream().mapToDouble(r -> toDouble(r.get("cascade_risk"))).max().orElse(1.0);
        final double mc = maxCascade > 0 ? maxCascade : 1.0;

        return scores.stream()
                .filter(r -> r.get("p_delay_gt15") instanceof Number)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> criticalityScore(r, mc)).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    private double criticalityScore(Map<String, Object> r, double maxCascade) {
        return 0.3 * toDouble(r.get("p_delay_gt15"))
                + 0.4 * toDouble(r.get("p_delay_gt60"))
                + 0.3 * (toDouble(r.get("cascade_risk")) / maxCascade);
    }

    public List<Map<String, Object>> getHighMisconnectionRisks(double threshold, int topN) {
        return getMisconnectionScores().stream()
                .filter(r -> r.get("p_misconnection") instanceof Number)
                .filter(r -> toDouble(r.get("p_misconnection")) > threshold)
                .sorted(Comparator.comparingDouble((Map<String, Object> r) -> toDouble(r.get("p_misconnection"))).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getRecoveryOptions(String flightNumber) {
        return getRecoveryRecommendations().stream()
                .filter(r -> r.get("disrupted_flight") instanceof String s && s.contains(flightNumber))
                .sorted(Comparator.comparingInt(this::rankOf))
                .collect(Collectors.toList());
    }

    private int rankOf(Map<String, Object> r) {
        return r.get("recommendation_rank") instanceof Number n ? n.intValue() : Integer.MAX_VALUE;
    }

    /** Risk rows for one flight, e.g. "G9406". Matches on the SAI column, ignoring spaces and case. */
    public List<Map<String, Object>> getFlightRisk(String flightNumber) {
        String needle = flightNumber.replace(" ", "").toLowerCase();
        return getRiskScores().stream()
                .filter(r -> String.valueOf(r.getOrDefault("SAI", "")).replace(" ", "").toLowerCase().contains(needle))
                .sorted(Comparator.comparing(r -> String.valueOf(r.getOrDefault("STD", ""))))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getFlightsByDate(String date) {
        return getRiskScores().stream()
                .filter(r -> r.get("STD") instanceof String s && s.startsWith(date))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getFlightsByRoute(String dep, String arr) {
        return getRiskScores().stream()
                .filter(r -> String.valueOf(r.getOrDefault("Dep", "")).equals(dep)
                        && String.valueOf(r.getOrDefault("Arr", "")).equals(arr))
                .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getFlightsByRegistration(String reg) {
        return getRiskScores().stream()
                .filter(r -> String.valueOf(r.getOrDefault("Reg", "")).equals(reg))
                // ISO timestamps sort correctly as strings (was hashCode(), which is effectively random)
                .sorted(Comparator.comparing(r -> String.valueOf(r.getOrDefault("STD", ""))))
                .collect(Collectors.toList());
    }

    /** First and last date (yyyy-MM-dd) found in the STD column, derived from the data. */
    public String getDateRange() {
        List<String> dates = getRiskScores().stream()
                .map(r -> r.get("STD"))
                .filter(v -> v instanceof String s && s.length() >= 10)
                .map(v -> ((String) v).substring(0, 10))
                .sorted()
                .collect(Collectors.toList());
        if (dates.isEmpty()) return "unknown";
        return dates.get(0) + " to " + dates.get(dates.size() - 1);
    }

    public Map<String, Object> getSummaryStatistics() {
        List<Map<String, Object>> scores = getRiskScores();
        Map<String, Object> stats = new LinkedHashMap<>();
        if (scores.isEmpty()) return stats;

        double avg15 = scores.stream().filter(r -> r.get("p_delay_gt15") instanceof Number)
                .mapToDouble(r -> toDouble(r.get("p_delay_gt15"))).average().orElse(0);
        double avg60 = scores.stream().filter(r -> r.get("p_delay_gt60") instanceof Number)
                .mapToDouble(r -> toDouble(r.get("p_delay_gt60"))).average().orElse(0);
        double avgCascade = scores.stream().filter(r -> r.get("cascade_risk") instanceof Number)
                .mapToDouble(r -> toDouble(r.get("cascade_risk"))).average().orElse(0);
        long highRisk = scores.stream().filter(r -> toDouble(r.get("p_delay_gt15")) > 0.7).count();
        long severe = scores.stream().filter(r -> toDouble(r.get("p_delay_gt60")) > 0.3).count();
        long uniqueRoutes = scores.stream().map(r -> r.get("Dep") + "-" + r.get("Arr")).distinct().count();

        stats.put("totalFlights", scores.size());
        stats.put("dateRange", getDateRange());
        stats.put("avgDelayProbability15min", String.format("%.1f%%", avg15 * 100));
        stats.put("avgDelayProbability60min", String.format("%.1f%%", avg60 * 100));
        stats.put("avgCascadeRisk", String.format("%.2f", avgCascade));
        stats.put("highRiskFlights", highRisk);
        stats.put("severeDelayFlights", severe);
        stats.put("uniqueRoutes", uniqueRoutes);
        return stats;
    }

    // ------------------------------------------------------------------
    // Structured response (REST / DTO path)
    // ------------------------------------------------------------------

    public DisruptionAnalysisResponse analyzeDisruption(String query) {
        Intent in = detectIntent(query);
        DisruptionAnalysisResponse response = new DisruptionAnalysisResponse();

        Map<String, Object> stats = getSummaryStatistics();
        response.setSummary(String.format(
                "Disruption prediction data for %s: %s flights analyzed across %s routes. "
                        + "%s flights have more than 70%% probability of a delay over 15 min. "
                        + "%s flights have more than 30%% probability of a delay over 60 min.",
                stats.get("dateRange"), stats.get("totalFlights"), stats.get("uniqueRoutes"),
                stats.get("highRiskFlights"), stats.get("severeDelayFlights")));

        if (in.risk() || in.airport()) {
            response.setHighRiskFlights(getHighRiskFlights(10).stream()
                    .map(r -> new DisruptionAnalysisResponse.RiskFlight(
                            str(r, "SAI"), str(r, "Reg"), str(r, "Dep"), str(r, "Arr"), str(r, "STD"),
                            toDouble(r.get("p_delay_gt15")), toDouble(r.get("p_delay_gt60")),
                            toDouble(r.get("delay_p50_min")), toDouble(r.get("delay_p90_min")),
                            toDouble(r.get("cascade_risk"))))
                    .collect(Collectors.toList()));
        }

        if (in.cascade() || in.airport()) {
            response.setCascadeImpacts(getHighCascadeRiskFlights(10).stream()
                    .map(r -> {
                        String route = str(r, "Dep") + " → " + str(r, "Arr");
                        int legs = (int) toDouble(r.get("legs_next_12h"));
                        double cascade = toDouble(r.get("cascade_risk"));
                        String desc = String.format(
                                "Flight %s on route %s has cascade risk %.2f with %d downstream legs in 12h.",
                                str(r, "SAI"), route, cascade, legs);
                        return new DisruptionAnalysisResponse.CascadeImpact(str(r, "SAI"), route, cascade, legs, desc);
                    })
                    .collect(Collectors.toList()));
        }

        List<DisruptionAnalysisResponse.PassengerImpact> impacts = new ArrayList<>();
        if (in.passenger()) {
            getHighPriorityPassengerFlights(10).forEach(r -> impacts.add(
                    new DisruptionAnalysisResponse.PassengerImpact(
                            str(r, "flight"), str(r, "dep") + " → " + str(r, "arr"),
                            toDouble(r.get("priority_score")), 0, str(r, "explanation"))));
        }
        if (in.misconnection()) {
            getHighMisconnectionRisks(0.5, 10).forEach(r -> impacts.add(
                    new DisruptionAnalysisResponse.PassengerImpact(
                            str(r, "inbound_flight") + " → " + str(r, "outbound_flight"), str(r, "date"),
                            toDouble(r.get("p_misconnection")), 0,
                            String.format("Misconnection risk: %.0f%% (connection gap: %d min)",
                                    toDouble(r.get("p_misconnection")) * 100, (int) toDouble(r.get("conn_gap_min"))))));
        }
        if (!impacts.isEmpty()) response.setPassengerImpacts(impacts);

        if (in.recovery()) {
            String flightNum = extractFlightNumber(query);
            List<Map<String, Object>> data = flightNum != null
                    ? getRecoveryOptions(flightNum)
                    : getRecoveryRecommendations().stream()
                    .filter(r -> r.get("recommendation_rank") instanceof Number)
                    .sorted(Comparator.comparingInt(this::rankOf))
                    .limit(20)
                    .collect(Collectors.toList());
            response.setRecommendations(data.stream()
                    .map(r -> new DisruptionAnalysisResponse.Recommendation(
                            str(r, "disrupted_flight"), str(r, "alternative_flight"), str(r, "route_match"),
                            toDouble(r.get("recovery_score")), toDouble(r.get("total_recovery_cost")), str(r, "tier")))
                    .collect(Collectors.toList()));
        }

        return response;
    }

    // ------------------------------------------------------------------
    // LLM context (this is what the model actually sees)
    // ------------------------------------------------------------------

    public String getDataContextForRAG(String query) {
        Intent in = detectIntent(query);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> stats = getSummaryStatistics();

        double maxCascade = getRiskScores().stream()
                .mapToDouble(r -> toDouble(r.get("cascade_risk"))).max().orElse(0);

        // Legend: tells the model what the numbers mean so it does not guess
        sb.append("DATA LEGEND:\n")
                .append("- P(delay>15) and P(delay>60): model-predicted probability that a flight is delayed by more than 15 / 60 minutes.\n")
                .append(String.format("- Cascade risk: higher means more downstream impact on the rest of the aircraft rotation (observed maximum in data: %.2f).%n", maxCascade))
                .append("- Legs next 12h: number of downstream flights on the same aircraft in the next 12 hours.\n")
                .append("- Criticality score: 0.3*P(delay>15) + 0.4*P(delay>60) + 0.3*(cascade risk / max cascade risk). Range 0 to 1.\n")
                .append("- Misconnection risk: probability that a connecting passenger misses the outbound flight.\n")
                .append("- Tier: recovery option tier as given in the recovery file. Costs are in the file's currency (shown as $).\n\n");

        sb.append("SUMMARY: ").append(stats.getOrDefault("totalFlights", 0)).append(" flights, ")
                .append(stats.getOrDefault("uniqueRoutes", 0)).append(" routes, dates ")
                .append(stats.getOrDefault("dateRange", "unknown")).append(". ")
                .append(stats.getOrDefault("highRiskFlights", 0)).append(" flights have P(delay>15) above 70%. ")
                .append(stats.getOrDefault("severeDelayFlights", 0)).append(" flights have P(delay>60) above 30%. ")
                .append("Average P(delay>15) is ").append(stats.getOrDefault("avgDelayProbability15min", "n/a")).append(".\n\n");

        // A specific flight was named: show its rows first
        String flightNum = extractFlightNumber(query);
        if (flightNum != null) {
            List<Map<String, Object>> rows = getFlightRisk(flightNum);
            sb.append("DATA FOR ").append(flightNum).append(" (source: rotation_risk_scores_21-30Nov.csv):\n");
            if (rows.isEmpty()) {
                sb.append("  No rows found for this flight number.\n");
            }
            rows.stream().limit(10).forEach(r -> sb.append(riskLine(r)).append("\n"));
            sb.append("\n");
        }

        // Always include the critical list: it answers "major issues" and "critical flights" questions
        double mc = maxCascade > 0 ? maxCascade : 1.0;
        sb.append("TOP 10 CRITICAL FLIGHTS by criticality score (source: rotation_risk_scores_21-30Nov.csv):\n");
        getCriticalFlights(10).forEach(r ->
                sb.append(riskLine(r)).append(String.format(", criticality=%.2f%n", criticalityScore(r, mc))));
        sb.append("\n");

        if (in.risk() || in.airport()) {
            sb.append("TOP 10 BY P(delay>15) (source: rotation_risk_scores_21-30Nov.csv):\n");
            getHighRiskFlights(10).forEach(r -> sb.append(riskLine(r)).append("\n"));
            sb.append("\nTOP 10 BY P(delay>60) (source: rotation_risk_scores_21-30Nov.csv):\n");
            getSevereDelayFlights(10).forEach(r -> sb.append(riskLine(r)).append("\n"));
            sb.append("\n");
        }

        if (in.cascade() || in.airport()) {
            sb.append("TOP 10 BY CASCADE RISK (source: rotation_risk_scores_21-30Nov.csv):\n");
            getHighCascadeRiskFlights(10).forEach(r -> sb.append(riskLine(r)).append("\n"));
            sb.append("\n");
        }

        if (in.passenger()) {
            sb.append("TOP 10 PASSENGER PRIORITY (source: passenger_priority_scores.csv):\n");
            getHighPriorityPassengerFlights(10).forEach(r ->
                    sb.append(String.format("  - %s (%s→%s): priority_score=%.1f, %s%n",
                            str(r, "flight"), str(r, "dep"), str(r, "arr"),
                            toDouble(r.get("priority_score")), str(r, "explanation"))));
            sb.append("\n");
        }

        if (in.misconnection()) {
            sb.append("HIGH MISCONNECTION RISKS, probability above 50% (source: misconnection_risk_scores.csv):\n");
            List<Map<String, Object>> mis = getHighMisconnectionRisks(0.5, 10);
            if (mis.isEmpty()) sb.append("  None above 50%.\n");
            mis.forEach(r -> sb.append(String.format("  - %s → %s on %s: P(misconnection)=%.0f%%, connection gap=%d min%n",
                    str(r, "inbound_flight"), str(r, "outbound_flight"), str(r, "date"),
                    toDouble(r.get("p_misconnection")) * 100, (int) toDouble(r.get("conn_gap_min")))));
            sb.append("\n");
        }

        if (in.recovery()) {
            List<Map<String, Object>> recs = flightNum != null
                    ? getRecoveryOptions(flightNum)
                    : getRecoveryRecommendations().stream()
                    .filter(r -> r.get("recommendation_rank") instanceof Number)
                    .sorted(Comparator.comparingInt(this::rankOf))
                    .limit(15)
                    .collect(Collectors.toList());
            sb.append("RECOVERY RECOMMENDATIONS (source: recovery_recommendations.csv):\n");
            if (recs.isEmpty()) sb.append("  None found.\n");
            recs.forEach(r -> sb.append(String.format("  - %s → %s (%s): rank=%d, recovery_score=%.2f, cost=$%.0f, tier=%s%n",
                    str(r, "disrupted_flight"), str(r, "alternative_flight"), str(r, "route_match"),
                    rankOf(r) == Integer.MAX_VALUE ? -1 : rankOf(r),
                    toDouble(r.get("recovery_score")), toDouble(r.get("total_recovery_cost")), str(r, "tier"))));
            sb.append("\n");
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String riskLine(Map<String, Object> r) {
        return String.format("  - %s [%s] %s→%s STD=%s: P(delay>15)=%.0f%%, P(delay>60)=%.0f%%, median delay=%.0f min, P90 delay=%.0f min, cascade=%.2f, legs_next_12h=%d",
                str(r, "SAI"), str(r, "Reg"), str(r, "Dep"), str(r, "Arr"), str(r, "STD"),
                toDouble(r.get("p_delay_gt15")) * 100, toDouble(r.get("p_delay_gt60")) * 100,
                toDouble(r.get("delay_p50_min")), toDouble(r.get("delay_p90_min")),
                toDouble(r.get("cascade_risk")), (int) toDouble(r.get("legs_next_12h")));
    }

    private String str(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /** Extracts a flight number like G9406 (also "G9 406") from free text. */
    private String extractFlightNumber(String query) {
        if (query == null) return null;
        Matcher m = FLIGHT_NO.matcher(query);
        return m.find() ? "G9" + m.group(1) : null;
    }

    public void reloadData() {
        riskScores = null;
        passengerScores = null;
        misconnectionScores = null;
        recoveryRecommendations = null;
        log.info("Data cache cleared, will reload on next query");
    }
}