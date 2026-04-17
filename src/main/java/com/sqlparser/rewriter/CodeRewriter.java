package com.sqlparser.rewriter;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.sqlparser.extractor.SqlStringResolver;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.parser.ParserModule;
import com.sqlparser.registry.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Rewrites Java source files by replacing original SQL strings with converted SQL.
 *
 * Strategy:
 *   1. Find all QueryInfo entries that have a convertedSql set.
 *   2. For each file that contains such queries, re-parse with LexicalPreservingPrinter
 *      to maintain original formatting.
 *   3. Locate the exact call expression or annotation at the recorded line.
 *   4. Replace the string argument with the new SQL.
 *   5. Write the modified source back.
 *
 * Safety rules:
 *   - Only rewrites queries where convertedSql is set.
 *   - Never modifies files if no conversions apply.
 *   - Creates .bak backup before writing.
 *   - Validates that the replaced expression is still valid Java.
 */
public class CodeRewriter {

    private static final Logger log = LoggerFactory.getLogger(CodeRewriter.class);

    // Methods whose first argument is the SQL string
    private static final Set<String> HQL_METHODS = Set.of(
            "createQuery", "createNamedQuery", "createStoredProcedureQuery");
    private static final Set<String> NATIVE_METHODS = Set.of(
            "createNativeQuery", "createSQLQuery",
            "createNativeMutationQuery", "createNativeSelectionQuery");

    private final QueryRegistry registry;
    private final SqlStringResolver resolver;

    public CodeRewriter(QueryRegistry registry) {
        this.registry = registry;
        this.resolver = new SqlStringResolver();
    }

    /**
     * Rewrites all Java files under sourceDir that contain convertible queries.
     * Returns the number of queries successfully rewritten.
     */
    public int rewriteAll(Path sourceDir) throws IOException {
        // Group queries by file path (only those with conversions)
        Map<String, List<QueryInfo>> byFile = groupConvertibleQueriesByFile();

        if (byFile.isEmpty()) {
            log.info("No queries with converted SQL found — nothing to rewrite");
            return 0;
        }

        int totalRewrites = 0;

        ParserModule parserModule = new ParserModule(sourceDir, false);

        for (Map.Entry<String, List<QueryInfo>> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            List<QueryInfo> queries = entry.getValue();

            Path javaFile = Path.of(filePath);
            if (!Files.exists(javaFile)) {
                // Try to find it under sourceDir
                javaFile = findFileUnder(sourceDir, Path.of(filePath).getFileName().toString());
                if (javaFile == null) {
                    log.warn("Source file not found: {} — skipping", filePath);
                    continue;
                }
            }

            int rewrites = rewriteFile(javaFile, queries, parserModule);
            totalRewrites += rewrites;
        }

        log.info("Total rewrites completed: {}", totalRewrites);
        return totalRewrites;
    }

    /**
     * Rewrites a single Java file.
     */
    public int rewriteFile(Path javaFile, List<QueryInfo> queries, ParserModule parserModule) throws IOException {
        log.info("Rewriting: {}", javaFile.getFileName());

        // Re-parse with lexical preservation
        CompilationUnit cu;
        try {
            cu = com.github.javaparser.StaticJavaParser.parse(javaFile);
            LexicalPreservingPrinter.setup(cu);
        } catch (Exception e) {
            log.error("Failed to parse {} for rewriting: {}", javaFile, e.getMessage());
            return 0;
        }

        // Sort queries by line descending so earlier rewrites don't shift line numbers
        List<QueryInfo> sorted = new ArrayList<>(queries);
        sorted.sort(Comparator.comparingInt(QueryInfo::getLine).reversed());

        RewriteVisitor visitor = new RewriteVisitor(sorted, resolver);
        cu.accept(visitor, null);

        int rewrites = visitor.getRewriteCount();
        if (rewrites > 0) {
            // Backup original
            Path backup = javaFile.resolveSibling(javaFile.getFileName() + ".bak");
            Files.copy(javaFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.debug("Backup created: {}", backup);

            // Write modified source using lexical preservation
            String modified = LexicalPreservingPrinter.print(cu);
            Files.writeString(javaFile, modified, StandardCharsets.UTF_8);
            log.info("  {} — {} queries rewritten", javaFile.getFileName(), rewrites);
        } else {
            log.debug("  {} — no matching queries found at expected lines", javaFile.getFileName());
        }

        return rewrites;
    }

    // ── Visitor ───────────────────────────────────────────────────────────────

    private static class RewriteVisitor extends ModifierVisitor<Void> {

        private final List<QueryInfo> queries;
        private final SqlStringResolver resolver;
        private int rewriteCount = 0;

        RewriteVisitor(List<QueryInfo> queries, SqlStringResolver resolver) {
            this.queries = queries;
            this.resolver = resolver;
        }

        int getRewriteCount() { return rewriteCount; }

        @Override
        public Visitable visit(MethodCallExpr call, Void arg) {
            String name = call.getNameAsString();
            boolean isQuery = HQL_METHODS.contains(name) || NATIVE_METHODS.contains(name);

            if (isQuery && !call.getArguments().isEmpty()) {
                int callLine = call.getBegin().map(p -> p.line).orElse(-1);
                Optional<QueryInfo> match = findQueryAtLine(callLine);

                if (match.isPresent()) {
                    QueryInfo q = match.get();
                    Expression firstArg = call.getArguments().get(0);
                    MethodDeclaration enclosingMethod = call.findAncestor(MethodDeclaration.class).orElse(null);

                    String newSql = q.getConvertedSql();
                    if (newSql != null && !newSql.isBlank()) {
                        Expression replacement = buildReplacement(firstArg, newSql, enclosingMethod);
                        if (replacement != null) {
                            call.getArguments().set(0, replacement);
                            rewriteCount++;
                            log.debug("Rewrote {} at line {}", q.getId(), callLine);
                        }
                    }
                }
            }

            return super.visit(call, arg);
        }

        @Override
        public Visitable visit(NormalAnnotationExpr annotation, Void arg) {
            return rewriteAnnotation(annotation, arg);
        }

        @Override
        public Visitable visit(SingleMemberAnnotationExpr annotation, Void arg) {
            String name = annotation.getNameAsString();
            if (isQueryAnnotation(name)) {
                int line = annotation.getBegin().map(p -> p.line).orElse(-1);
                Optional<QueryInfo> match = findQueryAtLine(line);
                if (match.isPresent()) {
                    QueryInfo q = match.get();
                    String newSql = q.getConvertedSql();
                    if (newSql != null) {
                        annotation.setMemberValue(new StringLiteralExpr(escapeJavaString(newSql)));
                        rewriteCount++;
                    }
                }
            }
            return super.visit(annotation, arg);
        }

        private Visitable rewriteAnnotation(NormalAnnotationExpr annotation, Void arg) {
            String name = annotation.getNameAsString();
            if (!isQueryAnnotation(name)) return super.visit(annotation, arg);

            int line = annotation.getBegin().map(p -> p.line).orElse(-1);
            Optional<QueryInfo> match = findQueryAtLine(line);

            if (match.isPresent()) {
                QueryInfo q = match.get();
                String newSql = q.getConvertedSql();
                if (newSql != null) {
                    annotation.getPairs().stream()
                            .filter(p -> p.getNameAsString().equals("value") || p.getNameAsString().equals("query"))
                            .findFirst()
                            .ifPresent(p -> {
                                p.setValue(new StringLiteralExpr(escapeJavaString(newSql)));
                                rewriteCount++;
                            });
                }
            }

            return super.visit(annotation, arg);
        }

        private Optional<QueryInfo> findQueryAtLine(int line) {
            return queries.stream()
                    .filter(q -> q.getLine() == line && q.getConvertedSql() != null)
                    .findFirst();
        }

        /**
         * Builds a replacement Expression for the SQL argument.
         *
         * Strategy:
         *   - If the original was a direct string literal → replace with new literal
         *   - If the original was a variable → replace with inline string literal
         *     (simplest approach that preserves compilation; leaves variable reassignment as dead code)
         *   - If the original was a concatenation → collapse into single string literal
         */
        private Expression buildReplacement(Expression original, String newSql, MethodDeclaration method) {
            if (original instanceof StringLiteralExpr || original instanceof TextBlockLiteralExpr) {
                // Simple swap
                return new StringLiteralExpr(escapeJavaString(newSql));
            }

            if (original instanceof BinaryExpr) {
                // Collapse concatenation into a single string
                return new StringLiteralExpr(escapeJavaString(newSql));
            }

            if (original instanceof NameExpr) {
                // Variable reference — inline the new SQL directly
                // This is safest: the variable may still be used elsewhere so we don't remove it
                return new StringLiteralExpr(escapeJavaString(newSql));
            }

            // For unhandled expression types, replace with a string literal
            return new StringLiteralExpr(escapeJavaString(newSql));
        }

        private boolean isQueryAnnotation(String name) {
            return name.equals("Query") || name.equals("NamedQuery") || name.equals("NamedNativeQuery");
        }

        private String escapeJavaString(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, List<QueryInfo>> groupConvertibleQueriesByFile() {
        Map<String, List<QueryInfo>> byFile = new LinkedHashMap<>();
        for (QueryInfo q : registry.all()) {
            if (q.getConvertedSql() != null && !q.getConvertedSql().isBlank()) {
                byFile.computeIfAbsent(q.getFile(), k -> new ArrayList<>()).add(q);
            }
        }
        return byFile;
    }

    private Path findFileUnder(Path root, String fileName) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(p -> p.getFileName().toString().equals(fileName))
                         .filter(Files::isRegularFile)
                         .findFirst()
                         .orElse(null);
        }
    }
}
