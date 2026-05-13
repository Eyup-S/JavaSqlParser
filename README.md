# Java SQL Parser

Static analysis tool for extracting and re-injecting Hibernate/JPA and JDBC SQL queries during an Oracle → PostgreSQL migration.

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
# Basic — scan everything (Hibernate + JDBC)
java -jar parser.jar extract src/main/java

# Custom output directory
java -jar parser.jar extract src/main/java output/sql

# Oracle-only mode, skip test files
java -jar parser.jar extract src/main/java output --mode=oracle-only --exclude=.*Test.*

# Hibernate sources only (no JDBC)
java -jar parser.jar extract src/main/java output --source=hibernate

# JDBC sources only (prepareStatement / prepareCall)
java -jar parser.jar extract src/main/java output --source=jdbc

# Append mode — add JDBC queries to an existing registry without re-scanning Hibernate
java -jar parser.jar extract src/main/java output --source=jdbc --append
```

**Output:**
```
output/
  queries.sql           — all queries combined
  split/
    queries_native_sql__native_sql.sql
    queries_hql__native_sql.sql
    queries_hql__hql.sql
    queries_jdbc__native_sql.sql
    ...
  registry.json         — query metadata (used by replace command and viz tool)
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
| `--source=all` | Scan both Hibernate/JPA and JDBC sources (default) |
| `--source=hibernate` | Scan only Hibernate/JPA sources (`createQuery`, `@Query`, etc.) |
| `--source=jdbc` | Scan only JDBC sources (`prepareStatement`, `prepareCall`) |
| `--append` | Load an existing `registry.json` first and add only new queries — skips locations already registered |
| `--yaml` | Also scan `.yaml`/`.yml` files for SQL strings (see YAML Scanning section below) |
| `--custom-hql=m1,m2` | Treat these method names as HQL queries (first argument is the HQL string) |
| `--custom-sql=m1,m2` | Treat these method names as native SQL queries (first argument is the SQL string) |
| `--exclude=<regex>` | Skip Java files whose absolute path matches the regex |

**Exclude examples:**

```bash
--exclude=.*Test.*                          # skip test files
--exclude=.*generated.*|.*legacy.*          # skip multiple directories
--exclude=.*(Test|Spec|IT)\.java$           # skip by suffix
```

**Typical two-pass workflow (Hibernate first, JDBC second):**

```bash
# Pass 1 — Hibernate scan
java -jar parser.jar extract src/main/java output --source=hibernate

# Pass 2 — append JDBC queries without re-scanning Hibernate
java -jar parser.jar extract src/main/java output --source=jdbc --append
```

**Custom method detection:**

```bash
# Your codebase has a custom query runner class with its own methods
java -jar parser.jar extract src/main/java output \
  --custom-hql=executeHql,runHqlQuery,findByHql \
  --custom-sql=executeSql,runNative,executeNativeQuery
```

The scanner treats the first argument of each listed method as the query string, exactly like `createQuery` or `createNativeQuery`.

**YAML scanning:**

```bash
# Scan Java sources + YAML/YML files in the same directory tree
java -jar parser.jar extract src/main/java output --yaml
```

---

## API and Language

Each extracted query gets two independent tags.

### API type — how the query is called in Java

| API | Java construct |
|-----|---------------|
| `NATIVE_SQL` | `createNativeQuery(...)`, `createNativeMutationQuery(...)` |
| `HQL` | `createQuery(...)`, `createNamedQuery(...)`, `createSelectionQuery(...)` |
| `ANNOTATION` | `@Query(...)`, `@NamedQuery(...)`, `@NamedNativeQuery(...)` |
| `JDBC` | `prepareStatement(sql)`, `prepareCall(sql)` |
| `YAML` | SQL string extracted from a `.yaml`/`.yml` file (enabled with `--yaml`) |

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
| `jdbc__native_sql.sql` | `prepareStatement` with native SQL | Run ora2pg |
| `hql__hql.sql` | `createQuery` with proper HQL/JPQL | Skip — no conversion needed |
| `annotation__hql.sql` | `@Query` with HQL | Skip — no conversion needed |
| `hql__ambiguous.sql` | Cannot determine language | Review manually |
| `jdbc__ambiguous.sql` | JDBC query, language unclear | Review manually |
| `yaml__native_sql.sql` | SQL in YAML file, native SQL | Run ora2pg |
| `yaml__ambiguous.sql` | SQL in YAML file, language unclear | Review manually |

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

## YAML Scanning

Enabled with the `--yaml` flag. Recursively scans every `.yaml` and `.yml` file in the source directory for SQL strings.

**Detection rule:** any scalar string value that contains a line starting with `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `WITH`, `CALL`, or `EXEC` is captured as a query.

**Supported YAML structures:**

```yaml
# Top-level key — value is a multiline block scalar
someQueryKey: |
  select col1, col2
  from some_table
  where condition = :param

# Nested under a mapping
section:
  anotherQuery: |
    select col1, count(*)
    from another_table
    group by col1

# Inside a list
items:
  - name: "item1"
    itemQuery: |
      select col1
      from item_table
      where active = 1
```

**Registry entries for the examples above:**

| Field | Value |
|-------|-------|
| API Type | `YAML` |
| Method | `popupQuery` / `reports.summaryQuery` / `popups[0].popupQuery` |
| File | absolute path to the `.yaml` file |
| Line | line number of the SQL string in the file |

The YAML key path is used as the method name so queries are identifiable in the registry and visualization tool. Language detection (`NATIVE_SQL` / `HQL` / `AMBIGUOUS`) applies to YAML queries the same way as Java queries.

---

## Visualization Tool

The `visualization/` directory contains a Streamlit app for reviewing, annotating, and converting queries interactively.

### Setup

```bash
cd visualization
pip install -r requirements.txt
```

### Run

```bash
streamlit run viz.py
```

Then open the URL printed in the terminal (default: `http://localhost:8501`).

### Features

- **Filter** queries by API type, language, review status, Oracle constructs, module, or free-text search
- **Charts** — API × Language distribution and Oracle construct frequency
- **Edit fields** — API Type, Language, Oracle flag, Review Status, Reviewed By
- **SQL panels** — Original SQL / ora2pg output / Final SQL (editable)
- **Accept ora2pg** — one-click copy of ora2pg output into the Final SQL field
- **Import ora2pg** — bulk-populate ora2pg output from a converted SQL file (sidebar)
- **Export converted SQL** — write Final SQL fields to a file with `BEGIN_QID/END_QID` markers for the `replace` command (sidebar)
- **Open in IntelliJ** — `idea://` link to jump directly to the source line

### Concurrent access

The tool is designed for teams where multiple people review the same `registry.json` at the same time:

- **File lock** — an exclusive lock is acquired before every write, preventing partial overlapping writes
- **Optimistic locking** — each query has a `version` counter; if someone else saved the same query while you were editing, you see a conflict dialog showing exactly which fields differ, then choose to discard your changes or force-save
- **Atomic writes** — the file is written to a temp file then renamed, so a crash mid-write never corrupts the registry
- **Auto-reload** — if the file changes on disk (another user saves), the UI reloads automatically on the next interaction

Queries that existed before versioning was introduced have no `version` field and are treated as version `0` — fully backward compatible.

### Open in IntelliJ setup

**macOS** — `idea://` works out of the box; no setup needed.

**Windows** — `idea://` is registered by the IntelliJ installer automatically.

**Ubuntu (snap install)** — snap IntelliJ does not register the URL handler. Run once per machine:

```bash
mkdir -p ~/.local/bin ~/.local/share/applications

cat > ~/.local/bin/idea-url-handler.sh << 'EOF'
#!/bin/bash
URL="$1"
FILE=$(python3 -c "import sys,urllib.parse; u='$URL'; print(urllib.parse.unquote(u.split('file=')[1].split('&')[0]))")
LINE=$(python3 -c "u='$URL'; print(u.split('line=')[1] if 'line=' in u else '1')")
intellij-idea-ultimate --line "$LINE" "$FILE" &
EOF
chmod +x ~/.local/bin/idea-url-handler.sh

cat > ~/.local/share/applications/idea-url-handler.desktop << 'EOF'
[Desktop Entry]
Name=IntelliJ IDEA URL Handler
Exec=/home/$USER/.local/bin/idea-url-handler.sh %u
Terminal=false
Type=Application
MimeType=x-scheme-handler/idea;
EOF

xdg-mime default idea-url-handler.desktop x-scheme-handler/idea
update-desktop-database ~/.local/share/applications/
```

---

## Typical workflow

```bash
# 1. Extract (both Hibernate and JDBC)
java -jar parser.jar extract src/main/java output --mode=oracle-only

# 2. Review in the viz tool — correct API/Language, mark review status

# 3. Import ora2pg output via the sidebar in viz tool
#    OR run ora2pg directly on a split file:
ora2pg -f output/split/queries_native_sql__native_sql.sql \
       -o output/split/converted_native_sql__native_sql.sql

# 4. Export Final SQL from viz tool → output/converted_from_review.sql
#    OR use the ora2pg output directly

# 5. Re-inject
java -jar parser.jar replace src/main/java \
  output/converted_from_review.sql \
  output/registry.json

# 6. Repeat step 5 for each converted file
```
