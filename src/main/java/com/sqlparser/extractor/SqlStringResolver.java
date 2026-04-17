package com.sqlparser.extractor;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Resolves a Java string Expression to its static string value by performing
 * backward data-flow analysis within the enclosing method scope.
 *
 * Handles:
 *   Case A — Direct string literal
 *   Case B — Variable reference
 *   Case C — Binary concatenation ("a" + "b")
 *   Case D — Incremental construction (sql += "...")
 *   Case E — Basic same-method call (buildQuery())
 */
public class SqlStringResolver {

    private static final Logger log = LoggerFactory.getLogger(SqlStringResolver.class);

    public static final String UNRESOLVED_MARKER = "<<UNRESOLVED>>";

    /**
     * Attempt to resolve an expression to a static string.
     * Returns the resolved SQL or UNRESOLVED_MARKER if dynamic/unknown.
     */
    public String resolve(Expression expr, MethodDeclaration enclosingMethod) {
        try {
            return resolveExpression(expr, enclosingMethod, new HashSet<>());
        } catch (Exception e) {
            log.debug("Resolution failed for expression at line {}: {}", expr.getBegin().map(p -> p.line).orElse(-1), e.getMessage());
            return UNRESOLVED_MARKER;
        }
    }

    private String resolveExpression(Expression expr, MethodDeclaration method, Set<String> visited) {
        if (expr == null) return UNRESOLVED_MARKER;

        // Case A: direct string literal
        if (expr instanceof StringLiteralExpr lit) {
            return lit.asString();
        }

        // Text block (Java 13+)
        if (expr instanceof TextBlockLiteralExpr tb) {
            return tb.asString();
        }

        // Case C: binary "+" concatenation
        if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = resolveExpression(bin.getLeft(), method, visited);
            String right = resolveExpression(bin.getRight(), method, visited);
            if (left.equals(UNRESOLVED_MARKER) || right.equals(UNRESOLVED_MARKER)) {
                // Return partial result if we got at least one side
                if (!left.equals(UNRESOLVED_MARKER)) return left + " " + UNRESOLVED_MARKER;
                if (!right.equals(UNRESOLVED_MARKER)) return UNRESOLVED_MARKER + " " + right;
                return UNRESOLVED_MARKER;
            }
            return left + right;
        }

        // Case B / D: variable reference — track assignments
        if (expr instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            if (visited.contains(varName)) {
                return UNRESOLVED_MARKER; // prevent infinite loops
            }
            visited.add(varName);
            return resolveVariable(varName, nameExpr, method, visited);
        }

        // Case E: simple method call with no arguments (same-class builder methods)
        if (expr instanceof MethodCallExpr call) {
            return resolveMethodCall(call, method, visited);
        }

        // Enclosed expression  (  expr  )
        if (expr instanceof EnclosedExpr enc) {
            return resolveExpression(enc.getInner(), method, visited);
        }

        // Conditional (ternary) — we cannot statically determine which branch
        if (expr instanceof ConditionalExpr) {
            return UNRESOLVED_MARKER;
        }

        log.debug("Unhandled expression type: {} at {}", expr.getClass().getSimpleName(),
                expr.getBegin().map(p -> p.line).orElse(-1));
        return UNRESOLVED_MARKER;
    }

    /**
     * Resolves a variable by scanning for the last assignment before the usage point.
     * Handles:
     *   - VariableDeclarator  String sql = "...";
     *   - AssignExpr          sql = "...";
     *   - AssignExpr +=       sql += "...";
     */
    private String resolveVariable(String varName, NameExpr usage, MethodDeclaration method, Set<String> visited) {
        int usageLine = usage.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        // Collect all assignments to this variable, ordered by line
        List<Assignment> assignments = new ArrayList<>();
        collectAssignments(varName, method, assignments);

        // Sort by line
        assignments.sort(Comparator.comparingInt(a -> a.line));

        // Apply assignments in order up to usage line
        // We simulate the accumulated string
        String accumulated = null;
        for (Assignment asgn : assignments) {
            if (asgn.line >= usageLine) break;

            switch (asgn.kind) {
                case INIT, ASSIGN -> {
                    String val = resolveExpression(asgn.value, method, new HashSet<>(visited));
                    if (!val.equals(UNRESOLVED_MARKER)) {
                        accumulated = val;
                    } else {
                        accumulated = UNRESOLVED_MARKER;
                    }
                }
                case CONCAT_ASSIGN -> {
                    String val = resolveExpression(asgn.value, method, new HashSet<>(visited));
                    if (accumulated == null) {
                        accumulated = val;
                    } else if (!accumulated.equals(UNRESOLVED_MARKER) && !val.equals(UNRESOLVED_MARKER)) {
                        accumulated = accumulated + val;
                    } else {
                        accumulated = UNRESOLVED_MARKER;
                    }
                }
            }
        }

        return accumulated != null ? accumulated : UNRESOLVED_MARKER;
    }

    private void collectAssignments(String varName, MethodDeclaration method, List<Assignment> result) {
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(VariableDeclarator vd, Void arg) {
                if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                    int line = vd.getBegin().map(p -> p.line).orElse(-1);
                    result.add(new Assignment(AssignmentKind.INIT, line, vd.getInitializer().get()));
                }
                super.visit(vd, arg);
            }

            @Override
            public void visit(AssignExpr ae, Void arg) {
                if (ae.getTarget() instanceof NameExpr ne && ne.getNameAsString().equals(varName)) {
                    int line = ae.getBegin().map(p -> p.line).orElse(-1);
                    AssignmentKind kind = ae.getOperator() == AssignExpr.Operator.PLUS ?
                            AssignmentKind.CONCAT_ASSIGN : AssignmentKind.ASSIGN;
                    result.add(new Assignment(kind, line, ae.getValue()));
                }
                super.visit(ae, arg);
            }
        }, null);
    }

    /**
     * Resolves a same-class no-arg method call by finding the return expression.
     * Only handles trivial cases: a method that just returns a string literal or concatenation.
     */
    private String resolveMethodCall(MethodCallExpr call, MethodDeclaration enclosingMethod, Set<String> visited) {
        // Only resolve scope-less or this.method() calls
        if (call.getScope().isPresent()) {
            Expression scope = call.getScope().get();
            // Skip if scope is not 'this'
            if (!(scope instanceof ThisExpr)) {
                return UNRESOLVED_MARKER;
            }
        }

        String calledName = call.getNameAsString();
        if (visited.contains("METHOD:" + calledName)) {
            return UNRESOLVED_MARKER;
        }

        // Find method declarations in the same class
        Optional<MethodDeclaration> targetMethod = enclosingMethod.findAncestor(
                com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .flatMap(cls -> cls.getMethods().stream()
                        .filter(m -> m.getNameAsString().equals(calledName) && m.getParameters().isEmpty())
                        .findFirst());

        if (targetMethod.isEmpty()) {
            return UNRESOLVED_MARKER;
        }

        // Collect return statements and try to resolve
        Set<String> newVisited = new HashSet<>(visited);
        newVisited.add("METHOD:" + calledName);

        List<String> returnValues = new ArrayList<>();
        targetMethod.get().accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ReturnStmt ret, Void arg) {
                if (ret.getExpression().isPresent()) {
                    returnValues.add(resolveExpression(ret.getExpression().get(), targetMethod.get(), newVisited));
                }
                super.visit(ret, arg);
            }
        }, null);

        if (returnValues.size() == 1) {
            return returnValues.get(0);
        }
        return UNRESOLVED_MARKER;
    }

    // ── Internal data structures ──────────────────────────────────────────────

    private enum AssignmentKind { INIT, ASSIGN, CONCAT_ASSIGN }

    private record Assignment(AssignmentKind kind, int line, Expression value) {}
}
