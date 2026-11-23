package com.ai.assistant.aiassistant.dto;

import java.util.List;

/**
 * Response DTO for structured disruption analysis queries.
 */
public class DisruptionAnalysisResponse {

    private String summary;
    private List<RiskFlight> highRiskFlights;
    private List<CascadeImpact> cascadeImpacts;
    private List<PassengerImpact> passengerImpacts;
    private List<Recommendation> recommendations;
    private List<Source> sources;

    public DisruptionAnalysisResponse() {
    }

    public DisruptionAnalysisResponse(String summary, List<RiskFlight> highRiskFlights,
                                      List<CascadeImpact> cascadeImpacts,
                                      List<PassengerImpact> passengerImpacts,
                                      List<Recommendation> recommendations,
                                      List<Source> sources) {
        this.summary = summary;
        this.highRiskFlights = highRiskFlights;
        this.cascadeImpacts = cascadeImpacts;
        this.passengerImpacts = passengerImpacts;
        this.recommendations = recommendations;
        this.sources = sources;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<RiskFlight> getHighRiskFlights() {
        return highRiskFlights;
    }

    public void setHighRiskFlights(List<RiskFlight> highRiskFlights) {
        this.highRiskFlights = highRiskFlights;
    }

    public List<CascadeImpact> getCascadeImpacts() {
        return cascadeImpacts;
    }

    public void setCascadeImpacts(List<CascadeImpact> cascadeImpacts) {
        this.cascadeImpacts = cascadeImpacts;
    }

    public List<PassengerImpact> getPassengerImpacts() {
        return passengerImpacts;
    }

    public void setPassengerImpacts(List<PassengerImpact> passengerImpacts) {
        this.passengerImpacts = passengerImpacts;
    }

    public List<Recommendation> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<Recommendation> recommendations) {
        this.recommendations = recommendations;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public static class RiskFlight {
        private String flightNumber;
        private String registration;
        private String departure;
        private String arrival;
        private String date;
        private double pDelay15;
        private double pDelay60;
        private double delayP50;
        private double delayP90;
        private double cascadeRisk;

        public RiskFlight() {
        }

        public RiskFlight(String flightNumber, String registration, String departure,
                          String arrival, String date, double pDelay15, double pDelay60,
                          double delayP50, double delayP90, double cascadeRisk) {
            this.flightNumber = flightNumber;
            this.registration = registration;
            this.departure = departure;
            this.arrival = arrival;
            this.date = date;
            this.pDelay15 = pDelay15;
            this.pDelay60 = pDelay60;
            this.delayP50 = delayP50;
            this.delayP90 = delayP90;
            this.cascadeRisk = cascadeRisk;
        }

        public String getFlightNumber() { return flightNumber; }
        public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }
        public String getRegistration() { return registration; }
        public void setRegistration(String registration) { this.registration = registration; }
        public String getDeparture() { return departure; }
        public void setDeparture(String departure) { this.departure = departure; }
        public String getArrival() { return arrival; }
        public void setArrival(String arrival) { this.arrival = arrival; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getpDelay15() { return pDelay15; }
        public void setpDelay15(double pDelay15) { this.pDelay15 = pDelay15; }
        public double getpDelay60() { return pDelay60; }
        public void setpDelay60(double pDelay60) { this.pDelay60 = pDelay60; }
        public double getDelayP50() { return delayP50; }
        public void setDelayP50(double delayP50) { this.delayP50 = delayP50; }
        public double getDelayP90() { return delayP90; }
        public void setDelayP90(double delayP90) { this.delayP90 = delayP90; }
        public double getCascadeRisk() { return cascadeRisk; }
        public void setCascadeRisk(double cascadeRisk) { this.cascadeRisk = cascadeRisk; }
    }

    public static class CascadeImpact {
        private String flightNumber;
        private String route;
        private double cascadeRisk;
        private int legsNext12h;
        private String description;

        public CascadeImpact() {
        }

        public CascadeImpact(String flightNumber, String route, double cascadeRisk,
                             int legsNext12h, String description) {
            this.flightNumber = flightNumber;
            this.route = route;
            this.cascadeRisk = cascadeRisk;
            this.legsNext12h = legsNext12h;
            this.description = description;
        }

        public String getFlightNumber() { return flightNumber; }
        public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }
        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
        public double getCascadeRisk() { return cascadeRisk; }
        public void setCascadeRisk(double cascadeRisk) { this.cascadeRisk = cascadeRisk; }
        public int getLegsNext12h() { return legsNext12h; }
        public void setLegsNext12h(int legsNext12h) { this.legsNext12h = legsNext12h; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class PassengerImpact {
        private String flightNumber;
        private String route;
        private double priorityScore;
        private int estimatedPassengers;
        private String explanation;

        public PassengerImpact() {
        }

        public PassengerImpact(String flightNumber, String route, double priorityScore,
                               int estimatedPassengers, String explanation) {
            this.flightNumber = flightNumber;
            this.route = route;
            this.priorityScore = priorityScore;
            this.estimatedPassengers = estimatedPassengers;
            this.explanation = explanation;
        }

        public String getFlightNumber() { return flightNumber; }
        public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }
        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
        public double getPriorityScore() { return priorityScore; }
        public void setPriorityScore(double priorityScore) { this.priorityScore = priorityScore; }
        public int getEstimatedPassengers() { return estimatedPassengers; }
        public void setEstimatedPassengers(int estimatedPassengers) { this.estimatedPassengers = estimatedPassengers; }
        public String getExplanation() { return explanation; }
        public void setExplanation(String explanation) { this.explanation = explanation; }
    }

    public static class Recommendation {
        private String disruptedFlight;
        private String alternativeFlight;
        private String route;
        private double recoveryScore;
        private double totalCost;
        private String tier;

        public Recommendation() {
        }

        public Recommendation(String disruptedFlight, String alternativeFlight, String route,
                              double recoveryScore, double totalCost, String tier) {
            this.disruptedFlight = disruptedFlight;
            this.alternativeFlight = alternativeFlight;
            this.route = route;
            this.recoveryScore = recoveryScore;
            this.totalCost = totalCost;
            this.tier = tier;
        }

        public String getDisruptedFlight() { return disruptedFlight; }
        public void setDisruptedFlight(String disruptedFlight) { this.disruptedFlight = disruptedFlight; }
        public String getAlternativeFlight() { return alternativeFlight; }
        public void setAlternativeFlight(String alternativeFlight) { this.alternativeFlight = alternativeFlight; }
        public String getRoute() { return route; }
        public void setRoute(String route) { this.route = route; }
        public double getRecoveryScore() { return recoveryScore; }
        public void setRecoveryScore(double recoveryScore) { this.recoveryScore = recoveryScore; }
        public double getTotalCost() { return totalCost; }
        public void setTotalCost(double totalCost) { this.totalCost = totalCost; }
        public String getTier() { return tier; }
        public void setTier(String tier) { this.tier = tier; }
    }

    public static class Source {
        private String file;
        private String chunk;

        public Source() {
        }

        public Source(String file, String chunk) {
            this.file = file;
            this.chunk = chunk;
        }

        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public String getChunk() { return chunk; }
        public void setChunk(String chunk) { this.chunk = chunk; }
    }
}
