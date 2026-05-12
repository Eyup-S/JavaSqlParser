# Oracle → ANSI SQL Conversion Reference

Oracle-specific constructs rewritten using standard ANSI/ISO SQL.
ANSI rewrites are database-agnostic and should run on PostgreSQL, SQL Server, MySQL 8+, DB2, and other standards-compliant engines.

**⚠ Needs rewrite** — Oracle-only; must be replaced.  
**✔ Already ANSI** — standard SQL; works as-is on most databases.  
**~ Partial** — ANSI equivalent exists but has limitations or edge cases.

---

## Date & Time

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `SYSDATE` | ⚠ | `CURRENT_DATE` or `CURRENT_TIMESTAMP` | SQL-92 | `SYSDATE` returns date+time in Oracle; use `CURRENT_DATE` if you need date only |
| `SYSTIMESTAMP` | ⚠ | `CURRENT_TIMESTAMP` | SQL-92 | Fractional seconds precision: `CURRENT_TIMESTAMP(6)` |
| `TRUNC(date, 'unit')` | ⚠ | No direct ANSI equivalent | — | Closest: `CAST(date AS DATE)` truncates to day; for other units use `EXTRACT` + arithmetic |
| `TRUNC(number)` | ~ | `TRUNCATE(number, 0)` | SQL:2003 | Or `FLOOR(ABS(n)) * SIGN(n)` — support varies |
| `ADD_MONTHS(d, n)` | ⚠ | `d + INTERVAL 'n' MONTH` | SQL-92 | Interval literal requires a constant; for variable `n` use `d + CAST(n AS INTERVAL MONTH)` |
| `MONTHS_BETWEEN(d1, d2)` | ⚠ | No single ANSI expression | — | Approximate: `(EXTRACT(YEAR FROM d1) - EXTRACT(YEAR FROM d2)) * 12 + (EXTRACT(MONTH FROM d1) - EXTRACT(MONTH FROM d2))` |
| `LAST_DAY(d)` | ⚠ | No direct ANSI equivalent | — | Workaround: `d - EXTRACT(DAY FROM d) * INTERVAL '1' DAY + INTERVAL '1' MONTH - INTERVAL '1' DAY` (verbose and engine-specific) |
| `NEXT_DAY(d, 'DAY')` | ⚠ | No ANSI equivalent | — | Needs vendor-specific logic; must be rewritten per target database |
| `LOCALTIMESTAMP` | ✔ | `LOCALTIMESTAMP` | SQL:1999 | Already ANSI standard |
| `SYSDATE - n` / `SYSDATE + n` | ⚠ | `CURRENT_TIMESTAMP - INTERVAL 'n' DAY` | SQL-92 | See note below |

> **⚠ Date arithmetic with plain numbers**
>
> Oracle treats a bare number as a number of days when added to or subtracted from a `DATE`:
> ```sql
> -- Oracle — works fine
> WHERE created_at > SYSDATE - 7
> WHERE expires_at < SYSDATE + 30
> ```
> PostgreSQL (and ANSI SQL) **do not allow adding or subtracting a plain integer to a timestamp or date**.
> You must use an `INTERVAL` literal:
> ```sql
> -- ANSI / PostgreSQL
> WHERE created_at > CURRENT_TIMESTAMP - INTERVAL '7' DAY
> WHERE expires_at < CURRENT_TIMESTAMP + INTERVAL '30' DAY
> ```
> For a variable number of days, cast the integer to an interval:
> ```sql
> -- variable n
> WHERE d > CURRENT_TIMESTAMP - (n * INTERVAL '1' DAY)
> -- or in PostgreSQL specifically:
> WHERE d > CURRENT_TIMESTAMP - MAKE_INTERVAL(days => n)
> ```

---

## Null & Conditional

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `NVL(expr, default)` | ⚠ | `COALESCE(expr, default)` | SQL-92 | `COALESCE` is preferred — also accepts more than 2 arguments |
| `NVL2(expr, if_not_null, if_null)` | ⚠ | `CASE WHEN expr IS NOT NULL THEN if_not_null ELSE if_null END` | SQL-92 | No shorter ANSI form |
| `DECODE(expr, v1,r1, v2,r2, ..., default)` | ⚠ | `CASE WHEN expr = v1 THEN r1 WHEN expr = v2 THEN r2 ... ELSE default END` | SQL-92 | Direct mechanical translation |
| `NULLIF(a, b)` | ✔ | `NULLIF(a, b)` | SQL-92 | Already ANSI |

---

## Conversion

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `TO_DATE(str, fmt)` | ~ | `DATE 'YYYY-MM-DD'` literal or `CAST(str AS DATE)` | SQL-92 | ANSI date literals only accept ISO format (`YYYY-MM-DD`); arbitrary format masks have no ANSI equivalent |
| `TO_CHAR(expr, fmt)` | ~ | `CAST(expr AS VARCHAR)` | SQL-92 | Format mask (`'YYYY-MM-DD'`, `'FM999'`) has no ANSI equivalent; formatting logic must move to application layer |
| `TO_NUMBER(expr)` | ⚠ | `CAST(expr AS NUMERIC)` | SQL-92 | |
| `CAST(expr AS type)` | ✔ | `CAST(expr AS type)` | SQL-92 | Already ANSI; verify Oracle-specific type names (`VARCHAR2` → `VARCHAR`, `NUMBER` → `NUMERIC`) |

> **⚠ TO_CHAR and TO_NUMBER with a format mask fail on PostgreSQL**
>
> Oracle's `TO_CHAR` and `TO_NUMBER` accept a second format-mask argument (`'YYYY-MM-DD'`, `'FM999,999'`, etc.).
> PostgreSQL has its own `TO_CHAR` / `TO_NUMBER` functions with a **different** format-mask syntax, and any Oracle-style mask will either produce wrong results or raise an error.
>
> **Without a format mask** — use `CAST`:
> ```sql
> -- Oracle
> TO_CHAR(amount)          →  CAST(amount AS VARCHAR)
> TO_NUMBER('42')          →  CAST('42' AS NUMERIC)
> ```
>
> **With a format mask** — the mask must be rewritten for the target database or moved to the application layer. There is no universal ANSI equivalent:
> ```sql
> -- Oracle
> TO_CHAR(hire_date, 'YYYY-MM-DD')   -- format mask differs in PostgreSQL
> TO_CHAR(salary, 'FM999,999.00')    -- FM prefix and separators are Oracle-specific
> TO_NUMBER('1,234.56', '999,999.99')
>
> -- PostgreSQL equivalents (mask syntax is different — not a simple rename)
> TO_CHAR(hire_date, 'YYYY-MM-DD')   -- same result here, but masks diverge for other patterns
> TO_CHAR(salary, '999,999.99')      -- FM prefix does not exist; trailing spaces behave differently
> TO_NUMBER('1,234.56', '9G999D99')  -- G = group separator, D = decimal separator in PostgreSQL
> ```
>
> **Rule of thumb:** if the query uses `TO_CHAR` or `TO_NUMBER` with a format mask, treat it as ⚠ needs rewrite — do not assume the mask works as-is on PostgreSQL.

---

## String Functions

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `INSTR(str, sub)` | ⚠ | `POSITION(sub IN str)` | SQL-92 | Two-argument form only |
| `INSTR(str, sub, pos, n)` | ⚠ | No ANSI equivalent | — | Multi-argument form (start position + occurrence) has no standard; needs application-layer logic |
| `SUBSTR(str, pos, len)` | ~ | `SUBSTRING(str FROM pos FOR len)` | SQL-92 | Oracle `SUBSTR` treats `pos=0` as `pos=1`; ANSI `SUBSTRING` does not |
| `LENGTH(str)` | ✔ | `CHAR_LENGTH(str)` | SQL-92 | `LENGTH` is also widely accepted but `CHAR_LENGTH` is the ANSI form for character count |
| `LPAD(str, n, pad)` | ~ | `LPAD(str, n, pad)` | SQL:2016 | Added to SQL standard late; widely supported but not in SQL-92 |
| `RPAD(str, n, pad)` | ~ | `RPAD(str, n, pad)` | SQL:2016 | Same as above |
| `LTRIM(str)` | ✔ | `TRIM(LEADING FROM str)` | SQL-92 | `LTRIM` itself is not ANSI; `TRIM(LEADING ...)` is |
| `RTRIM(str)` | ✔ | `TRIM(TRAILING FROM str)` | SQL-92 | Same |

---

## Regex Functions

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `REGEXP_LIKE(str, pat)` | ⚠ | `str SIMILAR TO pat` (limited) | SQL:1999 | `SIMILAR TO` is far less expressive than POSIX regex — most patterns need rewriting or vendor-specific functions |
| `REGEXP_SUBSTR(str, pat, ...)` | ⚠ | No ANSI equivalent | — | `SUBSTRING(str SIMILAR pat ESCAPE '!')` covers very basic cases only |
| `REGEXP_INSTR(str, pat, ...)` | ⚠ | No ANSI equivalent | — | No standard; must use vendor function |
| `REGEXP_REPLACE(src, pat, repl)` | ⚠ | No ANSI equivalent | — | No standard; must use vendor function |

---

## Aggregation

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `LISTAGG(col, delim) WITHIN GROUP (ORDER BY ...)` | ~ | `LISTAGG(col, delim) WITHIN GROUP (ORDER BY ...)` | SQL:2016 | Identical syntax is now ANSI; older engines pre-dating SQL:2016 need vendor alternatives (`STRING_AGG`, `GROUP_CONCAT`) |
| `WMSYS.WM_CONCAT(col)` | ⚠ | `LISTAGG(col, ',') WITHIN GROUP (ORDER BY 1)` | SQL:2016 | Undocumented Oracle function; always convert |

---

## Analytical / Window Functions

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `LAG(col, n, default) OVER (...)` | ✔ | `LAG(col, n, default) OVER (...)` | SQL:2003 | Already ANSI |
| `LEAD(col, n, default) OVER (...)` | ✔ | `LEAD(col, n, default) OVER (...)` | SQL:2003 | Already ANSI |
| `RANK() OVER (...)` | ✔ | `RANK() OVER (...)` | SQL:2003 | Already ANSI |
| `DENSE_RANK() OVER (...)` | ✔ | `DENSE_RANK() OVER (...)` | SQL:2003 | Already ANSI |
| `ROW_NUMBER() OVER (...)` | ✔ | `ROW_NUMBER() OVER (...)` | SQL:2003 | Already ANSI |

---

## Hierarchical Query

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `START WITH cond CONNECT BY PRIOR parent = child` | ⚠ | `WITH RECURSIVE cte AS (SELECT ... WHERE cond UNION ALL SELECT ... FROM t JOIN cte ON t.parent = cte.child) SELECT * FROM cte` | SQL:1999 | Mechanical but verbose; `LEVEL` becomes a depth counter column in the recursive part |
| `LEVEL` pseudocolumn | ⚠ | Add `depth + 1 AS depth` in the recursive term, seed with `1` in the base term | SQL:1999 | Part of the `WITH RECURSIVE` rewrite |
| `PRIOR col` | ⚠ | Reference to the CTE alias column in the recursive join condition | SQL:1999 | Part of the `WITH RECURSIVE` rewrite |

**CONNECT BY → WITH RECURSIVE template:**
```sql
-- Oracle
SELECT id, name, LEVEL
FROM tree
START WITH parent_id IS NULL
CONNECT BY PRIOR id = parent_id;

-- ANSI SQL:1999
WITH RECURSIVE tree_cte(id, name, depth) AS (
    SELECT id, name, 1
    FROM tree
    WHERE parent_id IS NULL          -- START WITH
    UNION ALL
    SELECT t.id, t.name, tc.depth + 1
    FROM tree t
    JOIN tree_cte tc ON t.parent_id = tc.id   -- CONNECT BY PRIOR
)
SELECT id, name, depth FROM tree_cte;
```

---

## Special Tables & Pseudocolumns

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `FROM DUAL` | ⚠ | Remove — `SELECT expr` without `FROM` | SQL:2003 | `SELECT 1` with no `FROM` is ANSI since SQL:2003; MySQL/Oracle still need `FROM DUAL` on older versions |
| `ROWNUM < n` | ⚠ | `FETCH FIRST n ROWS ONLY` | SQL:2008 | For ordered limiting: add `ORDER BY` before `FETCH FIRST`; `ROWNUM` is evaluated before `ORDER BY` in Oracle — behaviour differs |
| `ROWNUM` in `WHERE` with ordering | ⚠ | `ROW_NUMBER() OVER (ORDER BY col) <= n` as subquery | SQL:2003 | Necessary when row ordering matters |
| `ROWID` | ⚠ | No ANSI equivalent | — | Physical row address; not portable — redesign the query to use a primary key |

**ROWNUM pagination → ANSI template:**
```sql
-- Oracle (top-N)
SELECT * FROM orders WHERE ROWNUM <= 10;

-- ANSI SQL:2008
SELECT * FROM orders FETCH FIRST 10 ROWS ONLY;

-- Oracle (keyset / ordered top-N)
SELECT * FROM (
    SELECT id, name, ROWNUM rn FROM orders ORDER BY created_at DESC
) WHERE rn BETWEEN 11 AND 20;

-- ANSI SQL:2003
SELECT id, name FROM (
    SELECT id, name, ROW_NUMBER() OVER (ORDER BY created_at DESC) AS rn
    FROM orders
) t WHERE rn BETWEEN 11 AND 20;
```

---

## Set Operations

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `MINUS` | ⚠ | `EXCEPT` | SQL-92 | Direct rename — identical semantics |
| `INTERSECT` | ✔ | `INTERSECT` | SQL-92 | Already ANSI |
| `UNION ALL` | ✔ | `UNION ALL` | SQL-92 | Already ANSI |

---

## Join Syntax

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `table1.col(+) = table2.col` (outer join) | ⚠ | `table1 LEFT JOIN table2 ON table1.col = table2.col` | SQL-92 | `(+)` on left side = RIGHT JOIN; `(+)` on right side = LEFT JOIN; complex `(+)` expressions may need careful translation |

---

## Sequences

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `seq.NEXTVAL` | ⚠ | `NEXT VALUE FOR seq` | SQL:2003 | Direct ANSI equivalent; supported in PostgreSQL, SQL Server, DB2 — not MySQL |
| `seq.CURRVAL` | ⚠ | `CURRENT VALUE FOR seq` | SQL:2003 | Less widely supported than `NEXT VALUE FOR`; PostgreSQL uses `CURRVAL('seq')` |

---

## Data Types

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `VARCHAR2(n)` | ⚠ | `VARCHAR(n)` | SQL-92 | Identical semantics |
| `NUMBER(p, s)` | ⚠ | `NUMERIC(p, s)` or `DECIMAL(p, s)` | SQL-92 | `NUMBER` without precision → `NUMERIC` (arbitrary precision) |
| `NUMBER` (no precision) | ⚠ | `NUMERIC` | SQL-92 | |
| `DATE` (Oracle includes time) | ~ | `TIMESTAMP` | SQL-92 | Oracle `DATE` stores date+time; ANSI `DATE` is date-only — use `TIMESTAMP` to preserve time component |
| `CLOB` | ~ | `CHARACTER LARGE OBJECT` / `CLOB` | SQL:1999 | `CLOB` is ANSI but length semantics vary |
| `BLOB` | ~ | `BINARY LARGE OBJECT` / `BLOB` | SQL:1999 | `BLOB` is ANSI |

---

## Miscellaneous

| Oracle | Status | ANSI SQL Equivalent | Standard | Notes |
|--------|:------:|---------------------|----------|-------|
| `GREATEST(a, b, ...)` | ~ | `GREATEST(a, b, ...)` | SQL:2003 | Added in SQL:2003; not in SQL-92; widely supported |
| `LEAST(a, b, ...)` | ~ | `LEAST(a, b, ...)` | SQL:2003 | Same as above |
| `MERGE INTO ... USING ... ON ... WHEN MATCHED` | ✔ | `MERGE INTO ... USING ... ON ... WHEN MATCHED` | SQL:2003 | Already ANSI — Oracle's syntax is the standard; minor dialect differences exist |
| `SYS_GUID()` | ⚠ | No ANSI equivalent | — | UUID generation is vendor-specific; there is no ANSI standard function |
| `USER` (pseudocolumn) | ⚠ | `CURRENT_USER` | SQL-92 | Direct ANSI replacement |
| `/*+ hint */` optimizer hints | ⚠ | Remove | — | Not part of any standard; silently ignored or causes errors on other databases |

---

## ANSI SQL Standard Version Reference

| Standard | Year | Key additions relevant here |
|----------|------|-----------------------------|
| SQL-92 | 1992 | `COALESCE`, `NULLIF`, `CASE`, `CAST`, `TRIM`, `POSITION`, `SUBSTRING`, `CURRENT_DATE`, `CURRENT_TIMESTAMP`, `EXCEPT`, `INTERSECT`, `OUTER JOIN` syntax |
| SQL:1999 | 1999 | `WITH RECURSIVE`, `SIMILAR TO`, window function foundations, `CLOB`/`BLOB` |
| SQL:2003 | 2003 | `ROW_NUMBER`/`RANK`/`DENSE_RANK`/`LAG`/`LEAD` window functions, `NEXT VALUE FOR` sequences, `MERGE`, `GREATEST`/`LEAST`, `SELECT` without `FROM` |
| SQL:2008 | 2008 | `FETCH FIRST n ROWS ONLY` |
| SQL:2016 | 2016 | `LISTAGG`, JSON functions |

---

## Quick Conversion Cheat Sheet

```sql
-- NVL → COALESCE
NVL(col, 0)                          →  COALESCE(col, 0)

-- DECODE → CASE
DECODE(status, 1,'A', 2,'B', 'C')   →  CASE status WHEN 1 THEN 'A' WHEN 2 THEN 'B' ELSE 'C' END

-- NVL2 → CASE
NVL2(col, 'yes', 'no')              →  CASE WHEN col IS NOT NULL THEN 'yes' ELSE 'no' END

-- SYSDATE → CURRENT_TIMESTAMP
-- ⚠ SYSDATE ± n (plain number) does NOT work in PostgreSQL — must use INTERVAL
WHERE created > SYSDATE - 7         →  WHERE created > CURRENT_TIMESTAMP - INTERVAL '7' DAY
WHERE expires < SYSDATE + 30        →  WHERE expires < CURRENT_TIMESTAMP + INTERVAL '30' DAY

-- TO_CHAR / TO_NUMBER without format mask → CAST
TO_CHAR(amount)                     →  CAST(amount AS VARCHAR)
TO_NUMBER('42')                     →  CAST('42' AS NUMERIC)
-- ⚠ TO_CHAR / TO_NUMBER WITH a format mask: rewrite mask for PostgreSQL or move to app layer

-- ROWNUM top-N → FETCH FIRST
WHERE ROWNUM <= 100                  →  FETCH FIRST 100 ROWS ONLY

-- MINUS → EXCEPT
SELECT id FROM a MINUS SELECT id FROM b  →  SELECT id FROM a EXCEPT SELECT id FROM b

-- Outer join → ANSI JOIN
FROM a, b WHERE a.id = b.a_id(+)    →  FROM a LEFT JOIN b ON a.id = b.a_id

-- Sequence
seq_name.NEXTVAL                     →  NEXT VALUE FOR seq_name

-- FROM DUAL → remove
SELECT SYSDATE FROM DUAL            →  SELECT CURRENT_TIMESTAMP

-- VARCHAR2, NUMBER → standard types
col VARCHAR2(100)                   →  col VARCHAR(100)
col NUMBER(10,2)                    →  col NUMERIC(10,2)
```
