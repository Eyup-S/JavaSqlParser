package com.sqlparser.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.sqlparser.model.ScanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Responsible for loading and parsing Java source files into AST CompilationUnits.
 *
 * Supports:
 *   - File-level exclusion via ScanConfig.excludeFilePattern (regex on absolute path)
 *   - Optional JavaParser symbol solver for deeper type resolution
 */
public class ParserModule {

    private static final Logger log = LoggerFactory.getLogger(ParserModule.class);

    private final ParserConfiguration config;

    public ParserModule(Path sourceRoot) {
        this(sourceRoot, true);
    }

    public ParserModule(Path sourceRoot, boolean enableSymbolSolver) {
        config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE);

        if (enableSymbolSolver && sourceRoot != null && Files.isDirectory(sourceRoot)) {
            try {
                CombinedTypeSolver typeSolver = new CombinedTypeSolver();
                typeSolver.add(new ReflectionTypeSolver(false));
                typeSolver.add(new JavaParserTypeSolver(sourceRoot));
                JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
                config.setSymbolResolver(symbolSolver);
                log.debug("Symbol solver configured for source root: {}", sourceRoot);
            } catch (Exception e) {
                log.warn("Failed to configure symbol solver — proceeding without: {}", e.getMessage());
            }
        }

        StaticJavaParser.setConfiguration(config);
    }

    /**
     * Parses a single Java file and returns its CompilationUnit.
     */
    public ParsedFile parseFile(Path javaFile) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(javaFile);
            log.debug("Parsed: {}", javaFile.getFileName());
            return new ParsedFile(javaFile, cu);
        } catch (IOException e) {
            log.error("Failed to parse file: {} — {}", javaFile, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Parse error in {} — {}", javaFile.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Recursively finds and parses all .java files under the given directory.
     * Applies no file exclusions.
     */
    public List<ParsedFile> parseDirectory(Path directory) {
        return parseDirectory(directory, ScanConfig.allQueries());
    }

    /**
     * Recursively finds and parses all .java files under the given directory.
     * Files whose absolute path matches ScanConfig.excludeFilePattern are skipped.
     */
    public List<ParsedFile> parseDirectory(Path directory, ScanConfig config) {
        List<ParsedFile> results = new ArrayList<>();

        if (!Files.isDirectory(directory)) {
            log.error("Not a directory: {}", directory);
            return results;
        }

        int[] skipped = {0};

        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .filter(Files::isRegularFile)
                  .forEach(javaFile -> {
                      String absolutePath = javaFile.toAbsolutePath().toString();

                      if (config.shouldExclude(absolutePath)) {
                          log.debug("Excluded by pattern: {}", javaFile.getFileName());
                          skipped[0]++;
                          return;
                      }

                      ParsedFile parsed = parseFile(javaFile);
                      if (parsed != null) {
                          results.add(parsed);
                      }
                  });
        } catch (IOException e) {
            log.error("Error walking directory: {} — {}", directory, e.getMessage());
        }

        log.info("Parsed {} Java files from: {} ({} excluded by pattern)",
                results.size(), directory, skipped[0]);
        return results;
    }

    /**
     * Container pairing a source path with its parsed AST.
     */
    public record ParsedFile(Path path, CompilationUnit compilationUnit) {
        public String fileName() {
            return path.getFileName().toString();
        }
    }
}
