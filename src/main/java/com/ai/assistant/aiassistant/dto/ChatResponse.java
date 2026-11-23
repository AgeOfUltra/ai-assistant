package com.ai.assistant.aiassistant.dto;

import java.util.List;

public class ChatResponse {

    private String response;
    private List<Source> sources;

    public ChatResponse() {
    }

    public ChatResponse(String response, List<Source> sources) {
        this.response = response;
        this.sources = sources;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
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

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }

        public String getChunk() {
            return chunk;
        }

        public void setChunk(String chunk) {
            this.chunk = chunk;
        }
    }
}
