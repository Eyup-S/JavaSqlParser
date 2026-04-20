# Oracle Constructs Reference

All Oracle-specific SQL constructs detected by the tool, grouped by category.

**✅ Critical** — must be converted before PostgreSQL will run the query.  
**—** — compatible or informational only; verify but usually no rewrite needed.

Total: **50 constructs** across **13 categories**.

---

## Date & Time

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `SYSDATE` | ✅ | `CURRENT_TIMESTAMP` or `NOW()` |
| `SYSTIMESTAMP` | ✅ | `CURRENT_TIMESTAMP(6)` or `clock_timestamp()` |
| `TRUNC(date)` | ✅ | `DATE_TRUNC('unit', date)` |
| `TRUNC(number)` | ✅ | `TRUNC(number)` |
| `ADD_MONTHS(d, n)` | ✅ | `d + INTERVAL 'n months'` |
| `MONTHS_BETWEEN(d1, d2)` | ✅ | `EXTRACT(MONTH FROM AGE(d1, d2))` — approximate |
| `LAST_DAY(d)` | ✅ | `DATE_TRUNC('month', d) + INTERVAL '1 month' - INTERVAL '1 day'` |
| `NEXT_DAY(d, 'DAY')` | ✅ | No direct equivalent — needs custom logic |
| `LOCALTIMESTAMP` | — | Compatible — verify timezone semantics |

---

## Null & Conditional

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `NVL(expr, default)` | ✅ | `COALESCE(expr, default)` |
| `NVL2(expr, if_not_null, if_null)` | ✅ | `CASE WHEN expr IS NOT NULL THEN if_not_null ELSE if_null END` |
| `DECODE(expr, v1, r1, ..., default)` | ✅ | `CASE WHEN expr = v1 THEN r1 ... ELSE default END` |
| `NULLIF(expr1, expr2)` | — | `NULLIF(expr1, expr2)` — SQL standard, compatible |

---

## Conversion

| Construct | Critical | Notes |
|-----------|:--------:|-------|
| `TO_DATE(str, fmt)` | — | Mostly compatible — verify Oracle format masks |
| `TO_CHAR(expr, fmt)` | — | Mostly compatible — verify format strings |
| `TO_NUMBER(expr)` | — | Or use `CAST(expr AS NUMERIC)` |
| `CAST(expr AS type)` | — | SQL standard — usually compatible; verify Oracle-specific type names |

---

## String Functions

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `INSTR(str, sub)` | ✅ | `POSITION(sub IN str)` or `STRPOS(str, sub)` |
| `INSTR(str, sub, pos, n)` | ✅ | Multi-argument form needs custom rewrite |
| `SUBSTR(str, pos, len)` | — | `SUBSTRING(str FROM pos FOR len)` |
| `LENGTH(str)` | — | `LENGTH(str)` — compatible |
| `LPAD(str, n, pad)` | — | `LPAD(str, n, pad)` — compatible |
| `RPAD(str, n, pad)` | — | `RPAD(str, n, pad)` — compatible |
| `LTRIM(str)` | — | `LTRIM(str)` — compatible |
| `RTRIM(str)` | — | `RTRIM(str)` — compatible |

---

## Regex Functions

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `REGEXP_LIKE(str, pat, flags)` | ✅ | `str ~ pat` or `str ~* pat` (case-insensitive) |
| `REGEXP_SUBSTR(str, pat, ...)` | ✅ | `SUBSTRING(str FROM pattern)` — syntax differs significantly |
| `REGEXP_INSTR(str, pat, ...)` | ✅ | No direct equivalent — use custom function or `REGEXP_MATCHES` |
| `REGEXP_REPLACE(src, pat, repl, ...)` | ✅ | `REGEXP_REPLACE(src, pat, repl [,flags])` — argument order differs |

---

## Aggregation

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `LISTAGG(col, delim) WITHIN GROUP (ORDER BY ...)` | ✅ | `STRING_AGG(col, delim ORDER BY ...)` |

---

## Analytical / Window Functions

| Construct | Critical | Notes |
|-----------|:--------:|-------|
| `LAG() OVER()` | — | SQL standard — compatible |
| `LEAD() OVER()` | — | SQL standard — compatible |
| `RANK() OVER()` | — | SQL standard — compatible |
| `DENSE_RANK() OVER()` | — | SQL standard — compatible |
| `ROW_NUMBER() OVER()` | — | SQL standard — compatible |

---

## Hierarchical Query

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `CONNECT BY` | ✅ | `WITH RECURSIVE` |
| `START WITH` | ✅ | Root condition in `WITH RECURSIVE` |
| `LEVEL` | ✅ | Depth column — available inside `WITH RECURSIVE` |
| `PRIOR` | ✅ | Parent reference — part of `WITH RECURSIVE` rewrite |

---

## Special Tables & Pseudocolumns

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `FROM DUAL` | — | Remove — PostgreSQL allows `SELECT expr` without `FROM` |
| `ROWNUM` | ✅ | `LIMIT n` for simple cases; `ROW_NUMBER() OVER()` when ordering matters |
| `ROWID` | ✅ | `ctid` — internal physical address, unstable across vacuums |

---

## Set Operations

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `MINUS` | ✅ | `EXCEPT` |
| `INTERSECT` | — | `INTERSECT` — SQL standard, compatible |

---

## Join Syntax

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `table(+)` outer join | ✅ | `LEFT JOIN` / `RIGHT JOIN` in ANSI syntax |

---

## Sequences

| Construct | Critical | PostgreSQL Equivalent |
|-----------|:--------:|----------------------|
| `sequence.NEXTVAL` | ✅ | `NEXTVAL('sequence_name')` |
| `sequence.CURRVAL` | ✅ | `CURRVAL('sequence_name')` |

---

## Miscellaneous

| Construct | Critical | Notes |
|-----------|:--------:|-------|
| `GREATEST(...)` | — | Compatible |
| `LEAST(...)` | — | Compatible |
| `MERGE INTO ... USING ... ON ... WHEN MATCHED` | ✅ | `INSERT ... ON CONFLICT DO UPDATE` |
| `SYS_GUID()` | ✅ | `gen_random_uuid()` |
| `USER` (all-caps only) | — | `CURRENT_USER` — note: `User` (mixed case) is ignored to avoid matching JPA entity names |
| `VARCHAR2` | — | `VARCHAR` |
| `NUMBER(p, s)` | — | `NUMERIC(p, s)` |
| `WMSYS.WM_CONCAT(...)` | ✅ | `STRING_AGG(...)` — undocumented Oracle function |
| `/*+ hint */` optimizer hints | — | Silently ignored by PostgreSQL — safe to remove |

---

## Detection Notes

- Matching is case-insensitive for all constructs except `USER` — using `USER` in all-caps targets the Oracle pseudocolumn and avoids false positives on JPA entity names like `User` or `UserDto`.
- Function constructs (e.g. `NVL`, `DECODE`) require an opening parenthesis to match, so column or table names coincidentally named the same will not trigger a false positive.
- `MINUS` requires a word boundary and is excluded when followed by `=` (e.g. `value -= 1`) to avoid false positives.
- The `(+)` outer join pattern matches the literal characters and does not require word boundaries.
