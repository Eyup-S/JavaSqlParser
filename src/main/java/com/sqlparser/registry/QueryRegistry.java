package com.sqlparser.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
     */
    public void saveToJson(Path outputPath) throws IOException {
        List<QueryInfo> list = new ArrayList<>(queriesById.values());
        mapper.writeValue(outputPath.toFile(), list);
        log.info("Registry saved to: {} ({} entries)", outputPath, list.size());
    }

    /**
     * Loads a previously serialized registry from JSON and merges (or replaces) entries.
     */
    public void loadFromJson(Path inputPath) throws IOException {
        List<QueryInfo> loaded = mapper.readValue(inputPath.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, QueryInfo.class));
        for (QueryInfo info : loaded) {
            queriesById.put(info.getId(), info);
            // Restore location index so append-mode deduplication works
            if (info.getFile() != null && info.getClassName() != null
                    && info.getMethod() != null) {
                String locationKey = info.getFile() + ":" + info.getClassName()
                        + ":" + info.getMethod() + ":" + info.getLine();
                locationToId.put(locationKey, info.getId());
            }
        }
        // Restore counter to max existing ID to avoid collisions
        loaded.stream()
              .map(QueryInfo::getId)
              .filter(id -> id.matches("Q\\d+"))
              .mapToInt(id -> Integer.parseInt(id.substring(1)))
              .max()
              .ifPresent(max -> counter.set(Math.max(counter.get(), max)));

        log.info("Loaded {} queries from registry: {}", loaded.size(), inputPath);
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
