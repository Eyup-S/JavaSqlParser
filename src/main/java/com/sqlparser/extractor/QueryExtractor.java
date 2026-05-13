package com.sqlparser.extractor;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.sqlparser.model.*;
import com.sqlparser.parser.ParserModule.ParsedFile;
import com.sqlparser.registry.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Visits AST nodes of parsed Java files and extracts all Hibernate/JPA/Spring Data query points.
 *
 * ── Detected API methods ──────────────────────────────────────────────────────────────────────
 *
 * JPA EntityManager (javax.persistence / jakarta.persistence):
 *   createQuery(String)                       HQL/JPQL
 *   createQuery(String, Class)                HQL/JPQL
 *   createQuery(CriteriaQuery)                skipped — no SQL string
 *   createNamedQuery(String)                  HQL/JPQL or native (named)
 *   createNativeQuery(String)                 Native SQL
 *   createNativeQuery(String, Class)          Native SQL
 *   createNativeQuery(String, ResultSetMapping) Native SQL
 *   createStoredProcedureQuery(String)        stored procedure name, not SQL — skipped
 *
 * Hibernate Session (org.hibernate.Session) — covers both Hibernate 5.x and 6.x:
 *
 *   Hibernate 5.x (deprecated in 6):
 *   createQuery(String)                       HQL
 *   createQuery(String, Class)                HQL
 *   createSQLQuery(String)                    Native SQL (deprecated in 5.2)
 *
 *   Hibernate 6.x (new API):
 *   createQuery(String)                       HQL
 *   createQuery(String, Class)                HQL
 *   createNativeQuery(String)                 Native SQL
 *   createNativeQuery(String, Class)          Native SQL
 *   createNativeMutationQuery(String)         Native DML (INSERT/UPDATE/DELETE)
 *   createNativeSelectionQuery(String, Class) Native SELECT
 *   createMutationQuery(String)               HQL DML (UPDATE/DELETE in HQL)
 *   createSelectionQuery(String, Class)       HQL SELECT
 *
 *   Hibernate StatelessSession:
 *   createQuery(String)                       HQL
 *   createNativeQuery(String)                 Native SQL
 *
 * Spring Data JPA:
 *   @Query("...")                             HQL/JPQL
 *   @Query(value="...", nativeQuery=true)     Native SQL
 *   @NamedQuery(query="...")                  HQL
 *   @NamedNativeQuery(query="...")            Native SQL
 *
 * Spring JdbcTemplate / NamedParameterJdbcTemplate (common in mixed codebases):
 *   query(String, ...)                        Native SQL
 *   queryForObject(String, ...)               Native SQL
 *   queryForList(String, ...)                 Native SQL
 *   queryForMap(String, ...)                  Native SQL
 *   queryForRowSet(String, ...)               Native SQL
 *   update(String, ...)                       Native SQL
 *   execute(String)                           Native SQL
 *   batchUpdate(String)                       Native SQL
 *
 * ── Language detection ────────────────────────────────────────────────────────────────────────
 *   For methods that accept both HQL and SQL (createQuery), QueryLanguageDetector is applied
 *   to the resolved SQL string to classify it as HQL, NATIVE_SQL, or AMBIGUOUS.
 *
 * ── Scan modes ────────────────────────────────────────────────────────────────────────────────
 *   ALL         — all detected queries are registered
 *   ORACLE_ONLY — only queries where the resolved SQL contains at least one Oracle keyword
 */
public class QueryExtractor {

    private static final Logger log = LoggerFactory.getLogger(QueryExtractor.class);

    // ── HQL/JPQL methods — first arg is HQL string ─────────────────────────────────────────────
    private static final Set<String> HQL_METHODS = Set.of(
            "createQuery",           // EntityManager, Session (Hibernate 5+6)
            "createNamedQuery",      // EntityManager
            "createMutationQuery",   // Hibernate 6 — HQL UPDATE/DELETE
            "createSelectionQuery"   // Hibernate 6 — HQL SELECT
    );

    // ── Native SQL methods — first arg is raw SQL string ──────────────────────────────────────
    private static final Set<String> NATIVE_METHODS = Set.of(
            "createNativeQuery",          // EntityManager (JPA), Session (Hibernate 6)
            "createSQLQuery",             // Session (Hibernate 5.x, deprecated)
            "createNativeMutationQuery",  // Hibernate 6 — native DML
            "createNativeSelectionQuery"  // Hibernate 6 — native SELECT
    );

    // ── Spring JdbcTemplate methods — first arg is SQL string ─────────────────────────────────
    private static final Set<String> JDBC_TEMPLATE_METHODS = Set.of(
            "query",
            "queryForObject",
            "queryForList",
            "queryForMap",
            "queryForRowSet",
            "queryForStream",
            "update",
            "execute",
            "batchUpdate"
    );

    // ── Raw JDBC methods — first arg is SQL string ────────────────────────────────────────────
    // prepareStatement / prepareCall: Connection.prepareStatement(sql) — captures SQL for PreparedStatement
    // executeQuery / executeUpdate: Statement.executeQuery(sql) — raw Statement (no-arg form on PreparedStatement is skipped)
    private static final Set<String> JDBC_METHODS = Set.of(
            "prepareStatement",
            "prepareCall",
            "executeQuery",
            "executeUpdate"
    );

    private final QueryRegistry registry;
    private final SqlStringResolver resolver;
    private final ScanConfig scanConfig;

    public QueryExtractor(QueryRegistry registry) {
        this(registry, ScanConfig.allQueries());
    }

    public QueryExtractor(QueryRegistry registry, ScanConfig scanConfig) {
        this.registry = registry;
        this.resolver = new SqlStringResolver();
        this.scanConfig = scanConfig;
    }

    /**
     * Extracts queries from a single parsed file and registers them in the registry.
     */
    public List<QueryInfo> extract(ParsedFile parsedFile) {
        List<QueryInfo> extracted = new ArrayList<>();
        CompilationUnit cu = parsedFile.compilationUnit();

        cu.accept(new QueryVisitor(parsedFile, extracted), null);

        int kept = extracted.size();
        log.info("  {} → {} queries found (mode: {})", parsedFile.fileName(), kept, scanConfig.getMode());
        return extracted;
    }

    /**
     * Extracts queries from a list of parsed files.
     */
    public List<QueryInfo> extractAll(List<ParsedFile> parsedFiles) {
        List<QueryInfo> all = new ArrayList<>();
        for (ParsedFile pf : parsedFiles) {
            all.addAll(extract(pf));
        }
        log.info("Total queries extracted: {} (mode: {})", all.size(), scanConfig.getMode());
        return all;
    }

    // ── Visitor ──────────────────────────────────────────────────────────────

    private class QueryVisitor extends VoidVisitorAdapter<Void> {

        private final ParsedFile parsedFile;
        private final List<QueryInfo> results;
        private final Deque<String> classStack = new ArrayDeque<>();

        QueryVisitor(ParsedFile parsedFile, List<QueryInfo> results) {
            this.parsedFile = parsedFile;
            this.results = results;
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            classStack.push(n.getNameAsString());
            super.visit(n, arg);
            classStack.pop();
        }

        @Override
        public void visit(EnumDeclaration n, Void arg) {
            classStack.push(n.getNameAsString());
            super.visit(n, arg);
            classStack.pop();
        }

        // ── Annotation-based query detection ─────────────────────────────────

        @Override
        public void visit(NormalAnnotationExpr annotation, Void arg) {
            if (scanConfig.includesHibernate()) processAnnotation(annotation);
            super.visit(annotation, arg);
        }

        @Override
        public void visit(SingleMemberAnnotationExpr annotation, Void arg) {
            if (scanConfig.includesHibernate()) processAnnotation(annotation);
            super.visit(annotation, arg);
        }

        private void processAnnotation(AnnotationExpr annotation) {
            String name = annotation.getNameAsString();

            boolean isNative = false;
            Expression sqlExpr = null;

            switch (name) {
                case "Query" -> {
                    if (annotation instanceof SingleMemberAnnotationExpr sm) {
                        sqlExpr = sm.getMemberValue();
                    } else if (annotation instanceof NormalAnnotationExpr n) {
                        sqlExpr = findAnnotationPair(n, "value");
                        Expression nativeAttr = findAnnotationPair(n, "nativeQuery");
                        isNative = isTrueLiteral(nativeAttr);
                    }
                }
                case "NamedQuery" -> {
                    if (annotation instanceof NormalAnnotationExpr n) {
                        sqlExpr = findAnnotationPair(n, "query");
                    }
                }
                case "NamedNativeQuery" -> {
                    if (annotation instanceof NormalAnnotationExpr n) {
                        sqlExpr = findAnnotationPair(n, "query");
                        isNative = true;
                    }
                }
                default -> { return; }
            }

            if (sqlExpr != null) {
                int line = annotation.getBegin().map(p -> p.line).orElse(-1);
                MethodDeclaration enclosingMethod = annotation.findAncestor(MethodDeclaration.class).orElse(null);
                String methodName = enclosingMethod != null ? enclosingMethod.getNameAsString() : "<annotation>";
                String sql = resolveAnnotationString(sqlExpr);

                QueryInfo.QueryType type = isNative ? QueryInfo.QueryType.NATIVE_SQL : QueryInfo.QueryType.ANNOTATION;
                tryRegister(sql, parsedFile, currentClassName(), methodName, line, type, enclosingMethod);
            }
        }

        // ── Method call detection ─────────────────────────────────────────────

        @Override
        public void visit(MethodCallExpr call, Void arg) {
            String methodName = call.getNameAsString();

            if (scanConfig.includesHibernate()) {
                if (HQL_METHODS.contains(methodName) || scanConfig.getCustomHqlMethods().contains(methodName)) {
                    handleQueryCall(call, QueryInfo.QueryType.HQL);
                } else if (NATIVE_METHODS.contains(methodName) || scanConfig.getCustomNativeMethods().contains(methodName)) {
                    handleQueryCall(call, QueryInfo.QueryType.NATIVE_SQL);
                } else if (JDBC_TEMPLATE_METHODS.contains(methodName)) {
                    handleJdbcTemplateCall(call);
                }
            }

            if (scanConfig.includesJdbc() && JDBC_METHODS.contains(methodName)) {
                handleJdbcCall(call);
            }

            super.visit(call, arg);
        }

        private void handleQueryCall(MethodCallExpr call, QueryInfo.QueryType type) {
            if (call.getArguments().isEmpty()) return;

            Expression firstArg = call.getArguments().get(0);

            // createQuery(CriteriaQuery) — argument is not a string, skip
            // We detect this by checking if the arg is a string-resolvable expression
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            MethodDeclaration enclosingMethod = call.findAncestor(MethodDeclaration.class).orElse(null);
            String enclosingMethodName = enclosingMethod != null ? enclosingMethod.getNameAsString() : "<static>";

            String resolvedSql = enclosingMethod != null
                    ? resolver.resolve(firstArg, enclosingMethod)
                    : SqlStringResolver.UNRESOLVED_MARKER;

            tryRegister(resolvedSql, parsedFile, currentClassName(), enclosingMethodName, line, type, enclosingMethod);
        }

        /**
         * JdbcTemplate methods: first argument is the SQL string.
         * We distinguish from non-SQL uses by requiring the first argument to look like a string.
         */
        private void handleJdbcTemplateCall(MethodCallExpr call) {
            if (call.getArguments().isEmpty()) return;

            Expression firstArg = call.getArguments().get(0);
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            MethodDeclaration enclosingMethod = call.findAncestor(MethodDeclaration.class).orElse(null);
            String enclosingMethodName = enclosingMethod != null ? enclosingMethod.getNameAsString() : "<static>";

            String resolvedSql = enclosingMethod != null
                    ? resolver.resolve(firstArg, enclosingMethod)
                    : SqlStringResolver.UNRESOLVED_MARKER;

            // Only register if it looks like SQL (has SELECT/INSERT/UPDATE/DELETE/MERGE/WITH)
            if (resolvedSql.equals(SqlStringResolver.UNRESOLVED_MARKER) ||
                    !looksLikeSql(resolvedSql)) {
                return;
            }

            tryRegister(resolvedSql, parsedFile, currentClassName(), enclosingMethodName,
                    line, QueryInfo.QueryType.NATIVE_SQL, enclosingMethod);
        }

        /**
         * Raw JDBC methods: prepareStatement(sql), prepareCall(sql), executeQuery(sql), executeUpdate(sql).
         * All require a non-empty string first argument that looks like SQL.
         * PreparedStatement.executeQuery() / executeUpdate() with no args are invisible here
         * (they have 0 arguments and will be skipped by the isEmpty() guard).
         */
        private void handleJdbcCall(MethodCallExpr call) {
            if (call.getArguments().isEmpty()) return;

            Expression firstArg = call.getArguments().get(0);
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            MethodDeclaration enclosingMethod = call.findAncestor(MethodDeclaration.class).orElse(null);
            String enclosingMethodName = enclosingMethod != null ? enclosingMethod.getNameAsString() : "<static>";

            String resolvedSql = enclosingMethod != null
                    ? resolver.resolve(firstArg, enclosingMethod)
                    : SqlStringResolver.UNRESOLVED_MARKER;

            if (resolvedSql.equals(SqlStringResolver.UNRESOLVED_MARKER) || !looksLikeSql(resolvedSql)) {
                return;
            }

            tryRegister(resolvedSql, parsedFile, currentClassName(), enclosingMethodName,
                    line, QueryInfo.QueryType.JDBC, enclosingMethod);
        }

        // ── Registration with scan-mode filtering ─────────────────────────────

        private void tryRegister(String resolvedSql, ParsedFile file, String className,
                                 String methodName, int line, QueryInfo.QueryType apiType,
                                 MethodDeclaration enclosingMethod) {

            if (resolvedSql == null || resolvedSql.isBlank()) return;

            // De-duplicate by location — must use full path to match what registry.register() stores
            String locationKey = file.path().toString() + ":" + className + ":" + methodName + ":" + line;
            if (registry.isRegisteredByLocation(locationKey)) return;

            // ── SCAN MODE FILTER ──────────────────────────────────────────────
            if (!scanConfig.shouldInclude(resolvedSql)) {
                log.debug("  Skipped (mode=ORACLE_ONLY, no Oracle keyword): {}:{}:{}", file.fileName(), methodName, line);
                return;
            }

            // ── Language detection ────────────────────────────────────────────
            QueryLanguage lang = QueryLanguageDetector.detect(resolvedSql, apiType);

            // Oracle constructs
            List<String> oracleConstructs = OracleConstructDetector.detect(resolvedSql);
            boolean needsReview = OracleConstructDetector.requiresManualReview(resolvedSql)
                    || resolvedSql.contains(SqlStringResolver.UNRESOLVED_MARKER);

            QueryInfo info = registry.register(
                    file.path().toString(),
                    className,
                    methodName,
                    line,
                    resolvedSql,
                    resolvedSql,
                    apiType
            );

            info.setQueryLanguage(lang);
            info.setOracleConstructs(oracleConstructs);
            info.setNeedsManualReview(needsReview);

            if (resolvedSql.contains(SqlStringResolver.UNRESOLVED_MARKER)) {
                info.setResolutionStatus(QueryInfo.ResolutionStatus.PARTIAL);
            }

            results.add(info);
            log.debug("  Registered {} [{}→{}] at {}:{}:{}", info.getId(), apiType, lang, file.fileName(), methodName, line);
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private String currentClassName() {
            return classStack.isEmpty() ? "<unknown>" : classStack.peek();
        }

        private Expression findAnnotationPair(NormalAnnotationExpr annotation, String key) {
            return annotation.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals(key))
                    .map(MemberValuePair::getValue)
                    .findFirst()
                    .orElse(null);
        }

        private boolean isTrueLiteral(Expression expr) {
            return expr instanceof BooleanLiteralExpr b && b.getValue();
        }

        private String resolveAnnotationString(Expression expr) {
            if (expr instanceof StringLiteralExpr lit) return lit.asString();
            if (expr instanceof TextBlockLiteralExpr tb) return tb.asString();
            if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
                String l = resolveAnnotationString(bin.getLeft());
                String r = resolveAnnotationString(bin.getRight());
                if (l != null && r != null) return l + r;
            }
            return SqlStringResolver.UNRESOLVED_MARKER;
        }

        private static final java.util.regex.Pattern SQL_INDICATOR = java.util.regex.Pattern.compile(
                "^\\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|WITH|CALL|EXEC)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        private boolean looksLikeSql(String s) {
            return SQL_INDICATOR.matcher(s).find();
        }
    }
}
