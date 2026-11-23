package com.ai.assistant.aiassistant.service;

import com.ai.assistant.aiassistant.config.RAGProperties;
import com.ai.assistant.aiassistant.util.CsvUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private static final int CHUNK_SIZE = 1200;     // larger chunks = fewer embedding requests
    private static final int CHUNK_OVERLAP = 100;
    private static final int TOP_N_ROWS = 20;
    private static final int JSON_SAMPLE_ITEMS = 20;
    private static final int WRITE_BATCH_SIZE = 10;

    // Gemini free tier allows 100 embedding requests/minute, and each chunk is one request.
    // Stay under that with a safety margin. Raise this if you are on a paid tier.
    private static final int EMBED_REQUESTS_PER_MINUTE = 60;
    private static final int MAX_RETRIES = 5;
    private static final Pattern RETRY_IN = Pattern.compile("retry in ([0-9.]+)s", Pattern.CASE_INSENSITIVE);

    private static final String MANIFEST_NAME = ".ingest-manifest.properties";

    private static final String[] KEY_PATTERNS = {
            "p_delay_gt15", "p_delay_gt60", "p_misconnection",
            "priority_score", "recovery_score", "cascade_risk", "total_recovery_cost"
    };
    private static final String[] PROB_COLUMNS = {"p_delay_gt15", "p_delay_gt60", "p_misconnection"};

    private final VectorStore vectorStore;
    private final RAGProperties ragProperties;
    private final JsonMapper objectMapper;
    private final JdbcTemplate jdbc;

    // Reader for pulling single values out of a stream: trailing-token check must be off,
    // because the parser is intentionally left in the middle of the document
    private final ObjectReader streamTreeReader;

    // Prevents two ingestion runs from overlapping
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Remembers which files were already ingested (size + last-modified), so restarts don't re-embed them
    private final Properties manifest = new Properties();

    // Earliest time (epoch millis) the next embedding batch may be sent
    private long nextSlotMillis = 0;

    private record ScoredRow(double score, String text) {}

    public DocumentIngestionService(VectorStore vectorStore, RAGProperties ragProperties,
                                    JsonMapper objectMapper, JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.streamTreeReader = objectMapper.reader()
                .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Runs ingestion in the background so it never blocks or crashes application startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        log.info("Starting automatic document ingestion in background...");
        CompletableFuture.runAsync(this::ingestAll)
                .exceptionally(ex -> {
                    log.error("Background ingestion failed", ex);
                    return null;
                });
    }

    public void ingestAll() {
        ingestAll(false);
    }

    /** @param force when true, re-ingest every file even if it was ingested before and has not changed */
    public void ingestAll(boolean force) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Ingestion already running, skipping this request");
            return;
        }
        try {
            Path dataDir = Path.of(ragProperties.getDataDir());
            if (!Files.exists(dataDir)) {
                log.warn("Data directory does not exist: {}", dataDir);
                return;
            }

            List<Path> files = new ArrayList<>();
            Files.walkFileTree(dataDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.toString().toLowerCase();
                    if (name.endsWith(".csv") || name.endsWith(".json")) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            log.info("Found {} files to ingest", files.size());
            loadManifest();

            for (Path file : files) {
                try {
                    String key = file.toAbsolutePath().toString();
                    String fingerprint = fingerprint(file);

                    // Skip only if the manifest says "unchanged" AND the vector store really holds this
                    // file's chunks. Dropping or truncating the table therefore triggers re-ingestion.
                    if (!force
                            && fingerprint.equals(manifest.getProperty(key))
                            && vectorStoreHasChunks(key)) {
                        log.info("Skipping unchanged file: {}", file.getFileName());
                        continue;
                    }
                    ingestFile(file);
                    manifest.setProperty(key, fingerprint);
                    saveManifest();
                } catch (Exception e) {
                    log.error("Failed to ingest file: {}", file, e);
                }
            }
            log.info("Document ingestion completed");
        } catch (IOException e) {
            log.error("Failed to scan data directory", e);
        } finally {
            running.set(false);
        }
    }

    /** True if the vector store contains at least one chunk produced from this file. */
    private boolean vectorStoreHasChunks(String sourceKey) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM vector_store WHERE metadata->>'source_path' = ?",
                    Integer.class, sourceKey);
            return n != null && n > 0;
        } catch (Exception e) {
            // Table missing or unreadable: treat as not ingested
            log.warn("Could not check vector_store for {}: {}", sourceKey, e.getMessage());
            return false;
        }
    }

    public void ingestFile(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString().toLowerCase();
        String sourceKey = filePath.toAbsolutePath().toString();
        List<Document> documents = List.of();

        if (fileName.endsWith(".csv")) {
            documents = parseCsv(filePath, sourceKey);
        } else if (fileName.endsWith(".json")) {
            documents = parseJson(filePath, sourceKey);
        }

        if (documents.isEmpty()) {
            return;
        }

        // Remove whatever this file produced before, so changed files never leave stale chunks behind
        deleteExistingChunks(sourceKey);

        // Write in small batches to respect embedding API limits
        for (int i = 0; i < documents.size(); i += WRITE_BATCH_SIZE) {
            int end = Math.min(i + WRITE_BATCH_SIZE, documents.size());
            addWithRateLimit(documents.subList(i, end));
        }
        log.info("Ingested {} chunks from {}", documents.size(), filePath.getFileName());
    }

    private void deleteExistingChunks(String sourceKey) {
        try {
            var expression = new FilterExpressionBuilder().eq("source_path", sourceKey).build();
            vectorStore.delete(expression);
        } catch (Exception e) {
            // Not fatal: deterministic IDs still make the add() below an upsert
            log.warn("Could not delete old chunks for {}", sourceKey, e);
        }
    }

    public void clearAll() {
        var filterBuilder = new FilterExpressionBuilder();
        var expression = filterBuilder.in("file_type", "csv", "json").build();
        try {
            vectorStore.delete(expression);
            manifest.clear();
            Files.deleteIfExists(manifestPath());
            log.info("All documents cleared from vector store");
        } catch (Exception e) {
            log.warn("Could not clear documents via filter", e);
        }
    }

    // ------------------------------------------------------------------
    // Embedding API rate limiting + 429 retry
    // ------------------------------------------------------------------

    private void addWithRateLimit(List<Document> batch) {
        waitForSlot(batch.size());
        for (int attempt = 1; ; attempt++) {
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException e) {
                long waitMs = quotaRetryDelayMillis(e);
                if (waitMs < 0 || attempt >= MAX_RETRIES) {
                    throw e;
                }
                log.warn("Embedding quota hit, waiting {} ms before retry {}/{}", waitMs, attempt, MAX_RETRIES);
                sleep(waitMs);
            }
        }
    }

    /** Spaces batches out so the average stays under EMBED_REQUESTS_PER_MINUTE (one request per chunk). */
    private synchronized void waitForSlot(int requests) {
        long now = System.currentTimeMillis();
        long start = Math.max(now, nextSlotMillis);
        if (start > now) {
            sleep(start - now);
        }
        nextSlotMillis = start + (long) requests * (60_000L / EMBED_REQUESTS_PER_MINUTE);
    }

    /** Returns how long to wait if this is a quota (429) error, or -1 if it is some other error. */
    private long quotaRetryDelayMillis(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")
                    || msg.contains("Quota exceeded"))) {
                Matcher m = RETRY_IN.matcher(msg);
                if (m.find()) {
                    return (long) (Double.parseDouble(m.group(1)) * 1000) + 2000;
                }
                return 20_000;
            }
        }
        return -1;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ingestion interrupted", ie);
        }
    }

    // ------------------------------------------------------------------
    // Manifest: skip files that were already ingested and have not changed
    // ------------------------------------------------------------------

    // Optional: where to keep the manifest. Set this when the data directory is read-only (e.g. a :ro Docker mount).
    @Value("${rag.manifest-path:}")
    private String manifestPathProperty;

    private Path manifestPath() {
        if (manifestPathProperty != null && !manifestPathProperty.isBlank()) {
            return Path.of(manifestPathProperty);
        }
        return Path.of(ragProperties.getDataDir()).resolve(MANIFEST_NAME);
    }

    private String fingerprint(Path file) throws IOException {
        return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
    }

    private void loadManifest() {
        manifest.clear();
        Path path = manifestPath();
        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                manifest.load(in);
            } catch (IOException e) {
                log.warn("Could not read ingestion manifest, all files will be re-ingested", e);
            }
        }
    }

    private void saveManifest() {
        try {
            Path path = manifestPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                manifest.store(out, "files already ingested into the vector store");
            }
        } catch (IOException e) {
            log.warn("Could not save ingestion manifest", e);
        }
    }

    // ------------------------------------------------------------------
    // CSV: single streaming pass, constant memory
    // ------------------------------------------------------------------

    private List<Document> parseCsv(Path filePath, String sourceKey) throws IOException {
        String fileName = filePath.getFileName().toString();

        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return List.of();
            }

            String[] headers = CsvUtil.parseLine(headerLine);
            int n = headers.length;
            for (int i = 0; i < n; i++) {
                headers[i] = headers[i].trim();
            }

            // Running statistics on primitive arrays (no boxing)
            double[] sum = new double[n];
            double[] min = new double[n];
            double[] max = new double[n];
            long[] cnt = new long[n];
            Arrays.fill(min, Double.MAX_VALUE);
            Arrays.fill(max, -Double.MAX_VALUE);

            // Key column used for the Top-N section
            int keyIdx = -1;
            outer:
            for (String pattern : KEY_PATTERNS) {
                for (int i = 0; i < n; i++) {
                    if (headers[i].equalsIgnoreCase(pattern)) {
                        keyIdx = i;
                        break outer;
                    }
                }
            }

            // Bounded min-heap: never holds more than TOP_N_ROWS rows
            PriorityQueue<ScoredRow> top = new PriorityQueue<>(Comparator.comparingDouble(ScoredRow::score));

            // Risk distribution counters: [high, medium, low]
            int[] probIdx = new int[PROB_COLUMNS.length];
            long[][] buckets = new long[PROB_COLUMNS.length][3];
            for (int k = 0; k < PROB_COLUMNS.length; k++) {
                probIdx[k] = -1;
                for (int i = 0; i < n; i++) {
                    if (headers[i].equalsIgnoreCase(PROB_COLUMNS[k])) {
                        probIdx[k] = i;
                        break;
                    }
                }
            }

            long rowCount = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                rowCount++;

                String[] values = CsvUtil.parseLine(line);
                int limit = Math.min(values.length, n);
                double[] parsed = new double[n];
                boolean[] isNum = new boolean[n];

                for (int j = 0; j < limit; j++) {
                    try {
                        double v = Double.parseDouble(values[j].trim());
                        parsed[j] = v;
                        isNum[j] = true;
                        sum[j] += v;
                        cnt[j]++;
                        if (v < min[j]) min[j] = v;
                        if (v > max[j]) max[j] = v;
                    } catch (NumberFormatException ignored) {
                        // non-numeric value, skip
                    }
                }

                if (keyIdx >= 0 && isNum[keyIdx]) {
                    double score = parsed[keyIdx];
                    if (top.size() < TOP_N_ROWS || score > top.peek().score()) {
                        StringBuilder sb = new StringBuilder(); // built only if the row qualifies
                        for (int j = 0; j < limit; j++) {
                            if (j > 0) sb.append(", ");
                            sb.append(headers[j]).append("=").append(values[j].trim());
                        }
                        top.add(new ScoredRow(score, sb.toString()));
                        if (top.size() > TOP_N_ROWS) top.poll();
                    }
                }

                for (int k = 0; k < probIdx.length; k++) {
                    int idx = probIdx[k];
                    if (idx >= 0 && isNum[idx]) {
                        double v = parsed[idx];
                        if (v > 0.5) buckets[k][0]++;
                        else if (v > 0.2) buckets[k][1]++;
                        else buckets[k][2]++;
                    }
                }
            }

            // Build the summary text
            StringBuilder summary = new StringBuilder();
            summary.append("File: ").append(fileName).append("\n");
            summary.append("Total rows: ").append(rowCount).append("\n");
            summary.append("Columns: ").append(String.join(", ", headers)).append("\n\n");

            summary.append("=== Statistical Summary ===\n");
            for (int i = 0; i < n; i++) {
                if (cnt[i] > 0) {
                    summary.append(headers[i]).append(String.format(
                            ": mean=%.4f, min=%.4f, max=%.4f, count=%d%n",
                            sum[i] / cnt[i], min[i], max[i], cnt[i]));
                }
            }
            summary.append("\n");

            if (keyIdx >= 0 && !top.isEmpty()) {
                summary.append("=== Top ").append(TOP_N_ROWS).append(" Rows by ")
                        .append(headers[keyIdx]).append(" ===\n");
                top.stream()
                        .sorted(Comparator.comparingDouble(ScoredRow::score).reversed())
                        .forEach(r -> summary.append(r.text()).append("\n"));
                summary.append("\n");
            }

            for (int k = 0; k < probIdx.length; k++) {
                if (probIdx[k] >= 0 && cnt[probIdx[k]] > 0) {
                    summary.append("=== Distribution of ").append(headers[probIdx[k]]).append(" ===\n")
                            .append("High risk (>0.5): ").append(buckets[k][0]).append("\n")
                            .append("Medium risk (0.2-0.5): ").append(buckets[k][1]).append("\n")
                            .append("Low risk (<=0.2): ").append(buckets[k][2]).append("\n\n");
                }
            }

            return chunkText(summary.toString(), fileName, sourceKey, "csv");
        }
    }

    // ------------------------------------------------------------------
    // JSON: streaming parser, only samples are held in memory
    // ------------------------------------------------------------------

    private List<Document> parseJson(Path filePath, String sourceKey) throws IOException {
        String fileName = filePath.getFileName().toString();

        StringBuilder summary = new StringBuilder();
        summary.append("File: ").append(fileName).append("\n\n");

        try (InputStream in = Files.newInputStream(filePath);
             JsonParser p = objectMapper.createParser(in)) {

            JsonToken first = p.nextToken();

            if (first == JsonToken.START_ARRAY) {
                // Top-level array: the typical shape of model output
                summarizeStreamedArray(summary, "root", p);

            } else if (first == JsonToken.START_OBJECT) {
                // Top-level object: handle each field individually
                while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                    String key = p.currentName();
                    JsonToken valueToken = p.nextToken();

                    if (valueToken == JsonToken.START_ARRAY) {
                        summarizeStreamedArray(summary, key, p);
                    } else if (valueToken == JsonToken.START_OBJECT) {
                        JsonNode node = streamTreeReader.readTree(p);
                        summary.append("=== ").append(key).append(" ===\n");
                        convertJson(summary, node, key);
                        summary.append("\n");
                    } else {
                        summary.append(key).append(" = ").append(p.getValueAsString()).append("\n");
                    }
                }

            } else if (first != null) {
                summary.append("value = ").append(p.getValueAsString()).append("\n");
            }
        }

        return chunkText(summary.toString(), fileName, sourceKey, "json");
    }

    /**
     * Parser must be positioned on START_ARRAY. Keeps the first N items as samples,
     * counts the rest, and skips over them without building any objects.
     */
    private void summarizeStreamedArray(StringBuilder sb, String key, JsonParser p) {
        List<JsonNode> samples = new ArrayList<>();
        long total = 0;

        while (p.nextToken() != JsonToken.END_ARRAY) {
            if (total < JSON_SAMPLE_ITEMS) {
                samples.add(streamTreeReader.readTree(p));
            } else {
                p.skipChildren();
            }
            total++;
        }

        sb.append(key).append(" (array, ").append(total).append(" items):\n");
        for (int i = 0; i < samples.size(); i++) {
            appendArrayItem(sb, i, samples.get(i));
        }
        if (total > samples.size()) {
            sb.append("  ... and ").append(total - samples.size()).append(" more items\n");
        }
        sb.append("\n");
    }

    private void appendArrayItem(StringBuilder sb, int index, JsonNode item) {
        sb.append("  [").append(index).append("] ");
        if (item.isObject()) {
            item.properties().forEach(f ->
                    sb.append(f.getKey()).append("=").append(f.getValue().asText()).append(", "));
        } else {
            sb.append(item.asText());
        }
        sb.append("\n");
    }

    /** Used for nested objects that were already loaded as a tree. */
    private void convertJson(StringBuilder sb, JsonNode node, String prefix) {
        if (node.isObject()) {
            node.properties().forEach(field -> {
                String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                JsonNode value = field.getValue();
                if (value.isObject()) {
                    sb.append("=== ").append(key).append(" ===\n");
                    convertJson(sb, value, key);
                    sb.append("\n");
                } else if (value.isArray()) {
                    sb.append(key).append(" (array, ").append(value.size()).append(" items):\n");
                    int limit = Math.min(value.size(), JSON_SAMPLE_ITEMS);
                    for (int i = 0; i < limit; i++) {
                        appendArrayItem(sb, i, value.get(i));
                    }
                    if (value.size() > limit) {
                        sb.append("  ... and ").append(value.size() - limit).append(" more items\n");
                    }
                    sb.append("\n");
                } else {
                    sb.append(key).append(" = ").append(value.asText()).append("\n");
                }
            });
        } else if (node.isArray()) {
            int limit = Math.min(node.size(), JSON_SAMPLE_ITEMS);
            for (int i = 0; i < limit; i++) {
                convertJson(sb, node.get(i), prefix + "[" + i + "]");
            }
            if (node.size() > limit) {
                sb.append("... and ").append(node.size() - limit).append(" more items\n");
            }
        } else {
            sb.append(prefix).append(" = ").append(node.asText()).append("\n");
        }
    }

    // ------------------------------------------------------------------
    // Chunking: line-aware, so rows are never cut in half. Deterministic IDs (no duplicates).
    // ------------------------------------------------------------------

    private List<Document> chunkText(String text, String sourceFile, String sourceKey, String fileType) {
        List<Document> chunks = new ArrayList<>();

        if (text.length() <= CHUNK_SIZE) {
            chunks.add(buildChunk(text, sourceFile, sourceKey, fileType, 0));
            return chunks;
        }

        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            String chunk = text.substring(start, end);

            // Break only at a newline. The old code also broke at '.', which cut numbers like 0.8234 in half.
            if (end < text.length()) {
                int breakPoint = chunk.lastIndexOf('\n');
                if (breakPoint > CHUNK_OVERLAP) {
                    chunk = chunk.substring(0, breakPoint + 1);
                    end = start + breakPoint + 1;
                }
            }

            chunks.add(buildChunk(chunk, sourceFile, sourceKey, fileType, index));
            index++;

            // Stop once the end of the text is reached, otherwise this loops forever
            if (end >= text.length()) {
                break;
            }

            // Overlap by roughly CHUNK_OVERLAP characters, but begin the next chunk at a line start
            int overlapFrom = end - CHUNK_OVERLAP;
            int nextLine = text.indexOf('\n', overlapFrom);
            start = (nextLine >= 0 && nextLine < end) ? nextLine + 1 : end;
        }

        return chunks;
    }

    private Document buildChunk(String text, String sourceFile, String sourceKey, String fileType, int index) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source_file", sourceFile);
        metadata.put("source_path", sourceKey);
        metadata.put("file_type", fileType);
        metadata.put("chunk_index", index);

        // Same file + same chunk position = same ID, so re-ingesting upserts instead of duplicating
        String id = UUID.nameUUIDFromBytes((sourceKey + "#" + index).getBytes(StandardCharsets.UTF_8)).toString();
        return new Document(id, text, metadata);
    }
}