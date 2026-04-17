package com.sqlparser.extractor;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
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
 *   Case B — Variable reference (String sql = "..."; createQuery(sql))
 *   Case C — Binary concatenation ("a" + "b")
 *   Case D — Incremental construction (sql += "...")
 *   Case E — Basic same-method call (buildQuery())
 *   Case F — StringBuilder/StringBuffer accumulation
 *              new StringBuilder().append("...").append("...")
 *              sb.append("..."); sb.append("..."); createQuery(sb.toString())
 */
public class SqlStringResolver {

    private static final Logger log = LoggerFactory.getLogger(SqlStringResolver.class);

    public static final String UNRESOLVED_MARKER = "<<UNRESOLVED>>";

    private static final Set<String> STRING_BUILDER_TYPES = Set.of(
            "StringBuilder", "StringBuffer",
            "java.lang.StringBuilder", "java.lang.StringBuffer"
    );

    // ── Public entry point ────────────────────────────────────────────────────

    public String resolve(Expression expr, MethodDeclaration enclosingMethod) {
        try {
            return resolveExpression(expr, enclosingMethod, new HashSet<>());
        } catch (Exception e) {
            log.debug("Resolution failed for expression at line {}: {}",
                    expr.getBegin().map(p -> p.line).orElse(-1), e.getMessage());
            return UNRESOLVED_MARKER;
        }
    }

    // ── Expression dispatch ───────────────────────────────────────────────────

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
            String left  = resolveExpression(bin.getLeft(),  method, visited);
            String right = resolveExpression(bin.getRight(), method, visited);
            if (left.equals(UNRESOLVED_MARKER) && right.equals(UNRESOLVED_MARKER)) return UNRESOLVED_MARKER;
            if (left.equals(UNRESOLVED_MARKER))  return UNRESOLVED_MARKER + " " + right;
            if (right.equals(UNRESOLVED_MARKER)) return left + " " + UNRESOLVED_MARKER;
            return left + right;
        }

        // Case B / D: variable reference — track String assignments
        if (expr instanceof NameExpr nameExpr) {
            String varName = nameExpr.getNameAsString();
            if (visited.contains(varName)) return UNRESOLVED_MARKER;
            visited.add(varName);
            return resolveVariable(varName, nameExpr, method, visited);
        }

        // Case F / E: method call — handles toString(), same-class builder methods
        if (expr instanceof MethodCallExpr call) {
            return resolveMethodCall(call, method, visited);
        }

        // Enclosed expression  ( expr )
        if (expr instanceof EnclosedExpr enc) {
            return resolveExpression(enc.getInner(), method, visited);
        }

        // Conditional (ternary) — cannot determine statically
        if (expr instanceof ConditionalExpr) {
            return UNRESOLVED_MARKER;
        }

        log.debug("Unhandled expression type: {} at {}", expr.getClass().getSimpleName(),
                expr.getBegin().map(p -> p.line).orElse(-1));
        return UNRESOLVED_MARKER;
    }

    // ── Case B / D: String variable resolution ────────────────────────────────

    /**
     * Resolves a String variable by replaying all assignments up to the usage line.
     * Handles: init, reassign (=), and concat-assign (+=).
     */
    private String resolveVariable(String varName, NameExpr usage, MethodDeclaration method, Set<String> visited) {
        int usageLine = usage.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

        List<Assignment> assignments = new ArrayList<>();
        collectAssignments(varName, method, assignments);
        assignments.sort(Comparator.comparingInt(a -> a.line));

        String accumulated = null;
        for (Assignment asgn : assignments) {
            if (asgn.line >= usageLine) break;
            switch (asgn.kind) {
                case INIT, ASSIGN -> {
                    String val = resolveExpression(asgn.value, method, new HashSet<>(visited));
                    accumulated = val;
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
                    AssignmentKind kind = ae.getOperator() == AssignExpr.Operator.PLUS
                            ? AssignmentKind.CONCAT_ASSIGN : AssignmentKind.ASSIGN;
                    result.add(new Assignment(kind, line, ae.getValue()));
                }
                super.visit(ae, arg);
            }
        }, null);
    }

    // ── Case E / F: method call resolution ───────────────────────────────────

    private String resolveMethodCall(MethodCallExpr call, MethodDeclaration enclosingMethod, Set<String> visited) {
        String methodName = call.getNameAsString();

        // Case F: varName.toString() — check if the variable is a StringBuilder/Buffer
        if (methodName.equals("toString") && call.getArguments().isEmpty()
                && call.getScope().isPresent()
                && call.getScope().get() instanceof NameExpr scopeVar) {

            String varName = scopeVar.getNameAsString();
            int usageLine  = call.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);

            if (isStringBuilderVariable(varName, enclosingMethod)) {
                String sbResult = resolveStringBuilder(varName, usageLine, enclosingMethod, visited);
                if (!sbResult.equals(UNRESOLVED_MARKER)) return sbResult;
            }
        }

        // Case F (inline): new StringBuilder().append(...).append(...).toString()
        if (methodName.equals("toString") && call.getArguments().isEmpty()
                && call.getScope().isPresent()) {

            Expression scope = call.getScope().get();
            if (isStringBuilderChain(scope)) {
                List<Expression> appendArgs = new ArrayList<>();
                unwrapAppendChain(scope, appendArgs);
                return joinAppendArgs(appendArgs, enclosingMethod, visited);
            }
        }

        // Case F: bare append chain used directly (no toString wrapper seen)
        if (methodName.equals("append") && call.getScope().isPresent()) {
            List<Expression> appendArgs = new ArrayList<>();
            unwrapAppendChain(call, appendArgs);
            return joinAppendArgs(appendArgs, enclosingMethod, visited);
        }

        // Case E: scope-less or this.method() call — resolve in same class
        if (!call.getScope().isPresent() || call.getScope().get() instanceof ThisExpr) {
            return resolveSameClassCall(call, enclosingMethod, visited);
        }

        return UNRESOLVED_MARKER;
    }

    // ── Case F: StringBuilder resolution ─────────────────────────────────────

    /**
     * Reconstructs the SQL from a named StringBuilder/StringBuffer variable.
     * Collects:
     *   - Constructor argument (if any):  new StringBuilder("initial")
     *   - All .append(arg) calls on the variable up to usageLine
     */
    private String resolveStringBuilder(String varName, int usageLine,
                                        MethodDeclaration method, Set<String> visited) {
        List<AppendItem> items = new ArrayList<>();
        collectStringBuilderItems(varName, usageLine, method, items);

        if (items.isEmpty()) return UNRESOLVED_MARKER;

        return joinAppendArgs(
                items.stream().map(AppendItem::expr).toList(),
                method, visited);
    }

    /**
     * Collects all string contributions for a StringBuilder variable before usageLine:
     *   1. Constructor argument (if non-empty)
     *   2. .append(...) calls — handles standalone statements and chained calls
     */
    private void collectStringBuilderItems(String varName, int usageLine,
                                           MethodDeclaration method, List<AppendItem> result) {
        method.accept(new VoidVisitorAdapter<Void>() {

            @Override
            public void visit(VariableDeclarator vd, Void arg) {
                if (!vd.getNameAsString().equals(varName) || vd.getInitializer().isEmpty()) {
                    super.visit(vd, arg);
                    return;
                }
                int line = vd.getBegin().map(p -> p.line).orElse(-1);
                if (line >= usageLine) { super.visit(vd, arg); return; }

                Expression init = vd.getInitializer().get();

                // new StringBuilder("initial").append(...).append(...)
                // OR new StringBuilder().append(...).append(...)
                List<Expression> chain = new ArrayList<>();
                Expression root = unwrapAppendChainWithRoot(init, chain);

                // If the root is new StringBuilder/StringBuffer, extract constructor arg
                if (root instanceof ObjectCreationExpr oce
                        && STRING_BUILDER_TYPES.contains(oce.getType().getNameAsString())
                        && !oce.getArguments().isEmpty()) {
                    result.add(new AppendItem(line, oce.getArguments().get(0)));
                }

                // Add chained appends in left-to-right order
                for (Expression e : chain) {
                    result.add(new AppendItem(line, e));
                }

                super.visit(vd, arg);
            }

            @Override
            public void visit(MethodCallExpr mc, Void arg) {
                // Standalone: varName.append(...)  or  varName.append(...).append(...)
                int line = mc.getBegin().map(p -> p.line).orElse(-1);
                if (line >= usageLine) { super.visit(mc, arg); return; }

                if (!mc.getNameAsString().equals("append")) {
                    super.visit(mc, arg); return;
                }

                // Walk up the call chain to find if varName is at the root scope
                if (rootScopeIsVar(mc, varName)) {
                    // Collect the whole chain's append args in left-to-right order
                    List<Expression> chain = new ArrayList<>();
                    collectChainedAppendArgs(mc, chain);

                    // Only add if this statement is not already captured as part of an init
                    // (avoid double-counting chained appends inside variable initializers)
                    boolean insideInit = mc.findAncestor(VariableDeclarator.class)
                            .map(vd -> vd.getNameAsString().equals(varName))
                            .orElse(false);
                    if (!insideInit) {
                        for (Expression e : chain) {
                            result.add(new AppendItem(line, e));
                        }
                    }
                }
                // Do NOT call super.visit here — we've already collected the whole chain
            }
        }, null);

        result.sort(Comparator.comparingInt(AppendItem::line));
    }

    /**
     * Unwraps a chain of .append() calls and returns the leftmost root expression.
     * Fills appendArgs with each append's argument in left-to-right order.
     *
     * e.g., new StringBuilder("a").append("b").append("c")
     *   root  = ObjectCreationExpr("a")
     *   chain = ["b", "c"]
     */
    private Expression unwrapAppendChainWithRoot(Expression expr, List<Expression> appendArgs) {
        if (expr instanceof MethodCallExpr mc && mc.getNameAsString().equals("append")
                && mc.getArguments().size() == 1 && mc.getScope().isPresent()) {

            Expression root = unwrapAppendChainWithRoot(mc.getScope().get(), appendArgs);
            appendArgs.add(mc.getArguments().get(0));
            return root;
        }
        return expr; // base case: the root (NameExpr, ObjectCreationExpr, etc.)
    }

    /**
     * Collects only the append args from a chain (ignores non-append root).
     */
    private void unwrapAppendChain(Expression expr, List<Expression> appendArgs) {
        unwrapAppendChainWithRoot(expr, appendArgs);
    }

    /**
     * Collects all .append() args from a chain starting from mc downward (outermost first),
     * ultimately in left-to-right order.
     */
    private void collectChainedAppendArgs(MethodCallExpr mc, List<Expression> result) {
        unwrapAppendChain(mc, result);
    }

    /**
     * Returns true if the leftmost scope in an append chain is NameExpr(varName).
     */
    private boolean rootScopeIsVar(MethodCallExpr mc, String varName) {
        Expression current = mc;
        while (current instanceof MethodCallExpr m && m.getScope().isPresent()) {
            current = m.getScope().get();
        }
        return current instanceof NameExpr ne && ne.getNameAsString().equals(varName);
    }

    /**
     * Returns true if expr is a chain that ultimately resolves to a StringBuilder construction.
     * e.g., new StringBuilder().append("a").append("b")
     */
    private boolean isStringBuilderChain(Expression expr) {
        Expression current = expr;
        while (current instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            current = mc.getScope().get();
        }
        if (current instanceof ObjectCreationExpr oce) {
            return STRING_BUILDER_TYPES.contains(oce.getType().getNameAsString());
        }
        return false;
    }

    /**
     * Returns true if the variable was declared as a StringBuilder or StringBuffer.
     */
    private boolean isStringBuilderVariable(String varName, MethodDeclaration method) {
        boolean[] found = {false};
        method.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(VariableDeclarator vd, Void arg) {
                if (vd.getNameAsString().equals(varName)) {
                    String type = vd.getType().asString();
                    if (STRING_BUILDER_TYPES.contains(type)
                            || type.equals("var")) { // Java 10+ var inference
                        // For 'var', check the initializer
                        if (type.equals("var") && vd.getInitializer().isPresent()) {
                            found[0] = isStringBuilderExpr(vd.getInitializer().get());
                        } else {
                            found[0] = true;
                        }
                    }
                }
                super.visit(vd, arg);
            }
        }, null);
        return found[0];
    }

    private boolean isStringBuilderExpr(Expression expr) {
        if (expr instanceof ObjectCreationExpr oce) {
            return STRING_BUILDER_TYPES.contains(oce.getType().getNameAsString());
        }
        if (expr instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            return isStringBuilderExpr(mc.getScope().get());
        }
        return false;
    }

    /** Resolves all append arguments and joins them into a single SQL string. */
    private String joinAppendArgs(List<Expression> args, MethodDeclaration method, Set<String> visited) {
        if (args.isEmpty()) return UNRESOLVED_MARKER;
        StringBuilder sb = new StringBuilder();
        for (Expression arg : args) {
            // StringBuilder.append(x) accepts non-string types — skip obvious non-SQL args
            String val = resolveExpression(arg, method, new HashSet<>(visited));
            if (val.equals(UNRESOLVED_MARKER)) {
                sb.append(UNRESOLVED_MARKER);
            } else {
                sb.append(val);
            }
        }
        return sb.toString();
    }

    // ── Case E: same-class no-arg method resolution ───────────────────────────

    private String resolveSameClassCall(MethodCallExpr call, MethodDeclaration enclosingMethod,
                                        Set<String> visited) {
        String calledName = call.getNameAsString();
        if (visited.contains("METHOD:" + calledName)) return UNRESOLVED_MARKER;

        Optional<MethodDeclaration> targetMethod = enclosingMethod
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                .flatMap(cls -> cls.getMethods().stream()
                        .filter(m -> m.getNameAsString().equals(calledName)
                                && m.getParameters().isEmpty())
                        .findFirst());

        if (targetMethod.isEmpty()) return UNRESOLVED_MARKER;

        Set<String> newVisited = new HashSet<>(visited);
        newVisited.add("METHOD:" + calledName);

        List<String> returnValues = new ArrayList<>();
        targetMethod.get().accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ReturnStmt ret, Void arg) {
                if (ret.getExpression().isPresent()) {
                    returnValues.add(resolveExpression(
                            ret.getExpression().get(), targetMethod.get(), newVisited));
                }
                super.visit(ret, arg);
            }
        }, null);

        return returnValues.size() == 1 ? returnValues.get(0) : UNRESOLVED_MARKER;
    }

    // ── Internal data structures ──────────────────────────────────────────────

    private enum AssignmentKind { INIT, ASSIGN, CONCAT_ASSIGN }

    private record Assignment(AssignmentKind kind, int line, Expression value) {}

    private record AppendItem(int line, Expression expr) {}
}
