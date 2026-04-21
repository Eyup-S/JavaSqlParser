package com.sqlparser.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlparser.model.QueryInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central store for all extracted queries.
 * Assigns stable sequential IDs (Q0001, Q0002, ...) and manages persistence to JSON.
 * IDs are deterministic per run when files are processed in a consistent order.
 */
public class QueryRegistry {

    private static final Logger log = LoggerFactory.getLogger(QueryRegistry.class);

    private final Map<String, QueryInfo> queriesById = new LinkedHashMap<>();
    private final Map<String, String> locationToId = new HashMap<>();
    // Preserves raw JSON nodes for entries loaded from file so that Python-added fields
    // (reviewStatus, reviewedBy, ora2pgSql, ...) survive a Java save/load round-trip.
    private final Map<String, ObjectNode> rawNodes = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ObjectMapper mapper;

    public QueryRegistry() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Registers a new query and returns the QueryInfo with an assigned ID.
     */
    public QueryInfo register(String filePath, String className, String methodName,
                              int line, String originalSql, String resolvedSql,
                              QueryInfo.QueryType type) {
        String id = generateId();
        String hash = computeHash(resolvedSql);

        QueryInfo info = new QueryInfo(id, filePath, className, methodName, line,
                originalSql, resolvedSql, hash, type);

        queriesById.put(id, info);
        String locationKey = filePath + ":" + className + ":" + methodName + ":" + line;
        locationToId.put(locationKey, id);

        return info;
    }

    public boolean isRegisteredByLocation(String locationKey) {
        return locationToId.containsKey(locationKey);
    }

    public Optional<QueryInfo> findById(String id) {
        return Optional.ofNullable(queriesById.get(id));
    }

    public Collection<QueryInfo> all() {
        return Collections.unmodifiableCollection(queriesById.values());
    }

    public int size() {
        return queriesById.size();
    }

    /**
     * Applies converted SQL strings from a Map<id, convertedSql> to the registry entries.
     */
    public void applyConversions(Map<String, String> conversions) {
        int applied = 0;
        for (Map.Entry<String, String> entry : conversions.entrySet()) {
            QueryInfo info = queriesById.get(entry.getKey());
            if (info != null) {
                info.setConvertedSql(entry.getValue());
                applied++;
            } else {
                log.warn("No query found for ID {} during conversion apply", entry.getKey());
            }
        }
        log.info("Applied {} conversions to registry", applied);
    }

    /**
     * Serializes the entire registry to a JSON file.
     *
     * For entries that were loaded from a previous run, the original raw JSON node is used
     * as the base and Java-side fields are overlaid on top. This preserves any extra fields
     * written by external tools (viz.py review state: reviewStatus, reviewedBy, ora2pgSql, …)
     * that Java does not know about.
     *
     * For newly registered entries, the QueryInfo is serialized normally.
     */
    public void saveToJson(Path outputPath) throws IOException {
        ArrayNode result = mapper.createArrayNode();

        for (QueryInfo info : queriesById.values()) {
            ObjectNode raw = rawNodes.get(info.getId());
            if (raw != null) {
                // Start from the raw node (preserves Python-added fields), then overlay
                // current Java-side values so any Java updates (e.g. convertedSql from
                // the replace command) are reflected.
                ObjectNode merged = raw.deepCopy();
                ObjectNode fresh = (ObjectNode) mapper.valueToTree(info);
                merged.setAll(fresh); // fresh overwrites matching keys; unknown keys in raw survive
                result.add(merged);
            } else {
                result.add(mapper.valueToTree(info));
            }
        }

        mapper.writeValue(outputPath.toFile(), result);
        log.info("Registry saved to: {} ({} entries)", outputPath, queriesById.size());
    }

    /**
     * Loads a previously serialized registry from JSON.
     * Raw JSON nodes are retained so that saveToJson can round-trip unknown fields.
     */
    public void loadFromJson(Path inputPath) throws IOException {
        ArrayNode array = (ArrayNode) mapper.readTree(inputPath.toFile());

        for (com.fasterxml.jackson.databind.JsonNode node : array) {
            ObjectNode objectNode = (ObjectNode) node;
            QueryInfo info = mapper.treeToValue(objectNode, QueryInfo.class);

            queriesById.put(info.getId(), info);
            rawNodes.put(info.getId(), objectNode);

            // Restore location index so append-mode deduplication works
            if (info.getFile() != null && info.getClassName() != null
                    && info.getMethod() != null) {
                String locationKey = info.getFile() + ":" + info.getClassName()
                        + ":" + info.getMethod() + ":" + info.getLine();
                locationToId.put(locationKey, info.getId());
            }
        }

        // Restore counter to max existing ID to avoid collisions on newly registered entries
        queriesById.keySet().stream()
                   .filter(id -> id.matches("Q\\d+"))
                   .mapToInt(id -> Integer.parseInt(id.substring(1)))
                   .max()
                   .ifPresent(max -> counter.set(Math.max(counter.get(), max)));

        log.info("Loaded {} queries from registry: {}", queriesById.size(), inputPath);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String generateId() {
        return String.format("Q%04d", counter.incrementAndGet());
    }

    private String computeHash(String sql) {
        if (sql == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 16); // first 16 hex chars is enough for identification
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sql.hashCode());
        }
    }
}
