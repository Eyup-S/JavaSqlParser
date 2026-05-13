package com.sqlparser.extractor;

import com.sqlparser.model.*;
import com.sqlparser.registry.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.*;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scans YAML/YML files for SQL strings and registers them in the query registry.
 *
 * Any scalar string value in a YAML file that starts with SELECT/INSERT/UPDATE/DELETE/MERGE/WITH
 * is treated as a SQL query. The YAML key path (e.g. "queries.findUsers.sql") is used as the
 * method name in the registry so queries are identifiable.
 *
 * Activated with --yaml flag on the extract/report command.
 */
public class YamlQueryExtractor {

    private static final Logger log = LoggerFactory.getLogger(YamlQueryExtractor.class);

    // Matches if any line in the value starts with a SQL keyword (handles multiline block scalars)
    private static final Pattern SQL_INDICATOR = Pattern.compile(
            "^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH|CALL|EXEC)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final QueryRegistry registry;
    private final ScanConfig scanConfig;

    public YamlQueryExtractor(QueryRegistry registry, ScanConfig scanConfig) {
        this.registry = registry;
        this.scanConfig = scanConfig;
    }

    /**
     * Recursively finds .yaml/.yml files under the given directory and extracts SQL strings.
     * Returns the number of queries registered.
     */
    public int extractAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.warn("YAML scan: not a directory: {}", directory);
            return 0;
        }

        int[] count = {0};
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      String name = p.getFileName().toString().toLowerCase();
                      return name.endsWith(".yaml") || name.endsWith(".yml");
                  })
                  .forEach(file -> count[0] += extractFromFile(file));
        } catch (IOException e) {
            log.error("YAML scan: error walking directory {} — {}", directory, e.getMessage());
        }

        log.info("YAML scan: {} SQL queries found across YAML files in {}", count[0], directory);
        return count[0];
    }

    private int extractFromFile(Path file) {
        String absolutePath = file.toAbsolutePath().toString();
        if (scanConfig.shouldExclude(absolutePath)) {
            log.debug("YAML scan: excluded by pattern: {}", file.getFileName());
            return 0;
        }

        String className = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        int[] count = {0};

        // Use compose() for node-level access that carries Mark (line/column) info
        Yaml yaml = new Yaml();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Node root = yaml.compose(reader);
            if (root == null) return 0;
            count[0] = traverseNode(root, file.toAbsolutePath(), className, "");
        } catch (Exception e) {
            log.warn("YAML scan: failed to parse {} — {}", file.getFileName(), e.getMessage());
        }

        if (count[0] > 0) {
            log.info("YAML scan: {} queries found in {}", count[0], file.getFileName());
        }
        return count[0];
    }

    private int traverseNode(Node node, Path file, String className, String keyPath) {
        int count = 0;

        if (node instanceof ScalarNode scalar) {
            String value = scalar.getValue();
            if (value != null && !value.isBlank() && looksLikeSql(value)) {
                int line = scalar.getStartMark().getLine() + 1; // SnakeYAML lines are 0-indexed
                String method = keyPath.isEmpty() ? "<root>" : keyPath;
                if (tryRegister(value, file, className, method, line)) {
                    count++;
                }
            }
        } else if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                Node keyNode = tuple.getKeyNode();
                String key = keyNode instanceof ScalarNode s ? s.getValue() : "?";
                String childPath = keyPath.isEmpty() ? key : keyPath + "." + key;
                count += traverseNode(tuple.getValueNode(), file, className, childPath);
            }
        } else if (node instanceof SequenceNode sequence) {
            int i = 0;
            for (Node item : sequence.getValue()) {
                count += traverseNode(item, file, className, keyPath + "[" + i + "]");
                i++;
            }
        }

        return count;
    }

    private boolean tryRegister(String sql, Path file, String className, String method, int line) {
        String locationKey = file.toString() + ":" + className + ":" + method + ":" + line;
        if (registry.isRegisteredByLocation(locationKey)) return false;

        if (!scanConfig.shouldInclude(sql)) return false;

        QueryLanguage lang = QueryLanguageDetector.detect(sql, QueryInfo.QueryType.YAML);
        List<String> constructs = OracleConstructDetector.detect(sql);
        boolean needsReview = OracleConstructDetector.requiresManualReview(sql);

        QueryInfo info = registry.register(
                file.toString(), className, method, line, sql, sql, QueryInfo.QueryType.YAML);
        info.setQueryLanguage(lang);
        info.setOracleConstructs(constructs);
        info.setNeedsManualReview(needsReview);

        log.debug("YAML: registered {} [{}] from {}:{}", info.getId(), lang, file.getFileName(), line);
        return true;
    }

    private boolean looksLikeSql(String s) {
        return SQL_INDICATOR.matcher(s).find();
    }
}
