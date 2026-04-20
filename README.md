# Java SQL Parser

Static analysis tool for extracting and re-injecting Hibernate/JPA SQL queries during an Oracle → PostgreSQL migration.

## Build

```bash
mvn clean package -DskipTests
```

Produces `target/parser.jar`.

---

## Commands

### `extract` — Scan Java sources and export SQL

```bash
java -jar parser.jar extract <source-dir> [output-dir] [options]
```

```bash
# Basic — scan everything
java -jar parser.jar extract src/main/java

# Custom output directory
java -jar parser.jar extract src/main/java output/sql

# Oracle-only mode, skip test files
java -jar parser.jar extract src/main/java output --mode=oracle-only --exclude=.*Test.*

# Skip target folders and files named RP*.java
java -jar parser.jar extract src/main/java output --exclude=.*/target/.*|.*/RP[^/]*\.java$
```

**Output:**
```
output/
  queries.sql           — all queries combined
  split/
    queries_native_sql__native_sql.sql
    queries_hql__native_sql.sql
    queries_hql__hql.sql
    ...
  registry.json         — query metadata (used by replace command)
  report.txt            — Oracle construct breakdown
```

---

### `replace` — Re-inject converted SQL into Java source

Run this after passing a split file through ora2pg:

```bash
java -jar parser.jar replace <source-dir> <converted-sql-file> [registry-json]
```

```bash
java -jar parser.jar replace src/main/java \
  output/split/converted_native_sql__native_sql.sql \
  output/registry.json
```

- Only queries present in the converted file are rewritten — all others stay untouched
- Original `.java` files are backed up as `.java.bak` before modification

---

### `report` — Review report only (no SQL files written)

```bash
java -jar parser.jar report <source-dir> [output-dir] [options]
```

```bash
java -jar parser.jar report src/main/java output --mode=oracle-only
```

---

## Options

| Option | Description |
|--------|-------------|
| `--mode=all` | Extract every detected query (default) |
| `--mode=oracle-only` | Extract only queries containing Oracle-specific keywords (`SYSDATE`, `ROWNUM`, `NVL`, `CONNECT BY`, etc.) |
| `--exclude=<regex>` | Skip Java files whose absolute path matches the regex |

**Exclude examples:**

```bash
--exclude=.*Test.*                          # skip test files
--exclude=.*generated.*|.*legacy.*          # skip multiple directories
--exclude=.*(Test|Spec|IT)\.java$           # skip by suffix
```

---

## API and Language

Each extracted query gets two independent tags:

### API type — how the query is called in Java

| API | Java method |
|-----|------------|
| `NATIVE_SQL` | `createNativeQuery(...)`, `createNativeMutationQuery(...)` |
| `HQL` | `createQuery(...)`, `createNamedQuery(...)`, `createSelectionQuery(...)` |
| `ANNOTATION` | `@Query(...)`, `@NamedQuery(...)`, `@NamedNativeQuery(...)` |

### Language — what the SQL string actually contains

| Lang | Meaning |
|------|---------|
| `NATIVE_SQL` | Table-based SQL, Oracle constructs, snake_case/ALL_CAPS table names |
| `HQL` | Entity-based JPQL/HQL, PascalCase entity names, `JOIN FETCH`, `NEW com.example.Dto(...)` |
| `AMBIGUOUS` | Cannot be determined statically — needs manual review |

### Split files by combination

The tool writes one SQL file per `api × lang` combination so you can process only what needs conversion:

| File | Situation | Action |
|------|-----------|--------|
| `native_sql__native_sql.sql` | `createNativeQuery` with native SQL | Run ora2pg |
| `hql__native_sql.sql` | `createQuery` used with native SQL — **code bug** | Run ora2pg + change API to `createNativeQuery` |
| `annotation__native_sql.sql` | `@Query(nativeQuery=true)` with native SQL | Run ora2pg |
| `hql__hql.sql` | `createQuery` with proper HQL/JPQL | Skip — no conversion needed |
| `annotation__hql.sql` | `@Query` with HQL | Skip — no conversion needed |
| `hql__ambiguous.sql` | Cannot determine language | Review manually |

### Language detection — how it works

> **Note:** Language detection is heuristic-based and will not always be correct. Always verify the detected `lang` value in the visualization tool and correct it manually if needed.

The detector runs a 10-step priority chain — first match wins:

| Priority | Rule | Detected as |
|----------|------|-------------|
| 1 | Oracle keywords: `SYSDATE`, `ROWNUM`, `NVL`, `CONNECT BY`, `DECODE`, `DUAL`, etc. | `NATIVE_SQL` |
| 2 | Outer join syntax `(+)` | `NATIVE_SQL` |
| 3 | Raw SQL keywords: `INSERT INTO`, `UPDATE … SET`, `DELETE FROM`, `CREATE`, `DROP`, `ALTER` | `NATIVE_SQL` |
| 4 | HQL-specific syntax: `JOIN FETCH`, `NEW com.example.Dto(…)`, `UPDATE Entity SET`, `DELETE FROM Entity WHERE` | `HQL` |
| 5 | `SELECT *` | `NATIVE_SQL` (HQL does not use `*`) |
| 6 | `FROM PascalCase` entity name — strict pattern `[A-Z][a-z][a-zA-Z0-9]*` | `HQL` |
| 7 | `FROM snake_case` or `ALL_CAPS` table name | `NATIVE_SQL` |
| 8 | camelCase property access (`u.firstName`, `o.createdAt`) | `HQL` |
| 9 | `alias.property` pattern (`u.name`, `o.id`) | `HQL` |
| 10 | Underscore names (`user_id`, `created_at`) | `NATIVE_SQL` |

If nothing matches → `AMBIGUOUS`

**Known cases where detection can go wrong:**

- Query has `SELECT *` but targets an HQL entity — step 5 fires before step 6, detected as `NATIVE_SQL`
- Entity name is all-caps (e.g. `STATUS`, `TYPE`) — matches the table name rule, detected as `NATIVE_SQL`
- Very short queries with no `FROM` clause — may fall through to `AMBIGUOUS`
- Mixed queries with both HQL and native fragments — first matching rule wins

If the detected language is wrong, open the query in the visualization tool and correct **API Type** and **Language** manually before saving.

---

### Typical workflow

```bash
# 1. Extract
java -jar parser.jar extract src/main/java output --mode=oracle-only

# 2. Review report.txt — decide which split files need conversion

# 3. Run ora2pg on the files that need it (preserve BEGIN_QID/END_QID lines)
ora2pg -f output/split/queries_native_sql__native_sql.sql \
       -o output/split/converted_native_sql__native_sql.sql

# 4. Re-inject per converted file
java -jar parser.jar replace src/main/java \
  output/split/converted_native_sql__native_sql.sql \
  output/registry.json

# 5. Repeat step 4 for each converted file
```
