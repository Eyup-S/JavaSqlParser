import json
import os
import re
import subprocess
from pathlib import Path

import pandas as pd
import plotly.express as px
import sqlparse
import streamlit as st

st.set_page_config(page_title="SQL Query Inspector", page_icon="🔍", layout="wide")

# ── Constants ──────────────────────────────────────────────────────────────────

REVIEW_STATUSES = [
    "",
    "pending",
    "in progress",
    "reviewed - no change needed",
    "reviewed and updated",
    "reviewed - solution cannot be found",
    "needs further investigation",
]

# ── Helpers ────────────────────────────────────────────────────────────────────

def load_registry(path: str) -> list:
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    return raw if isinstance(raw, list) else raw.get("queries", [])

def save_registry(queries: list, path: str):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(queries, f, indent=2, ensure_ascii=False)

def parse_converted_sql_file(content: str) -> dict[str, str]:
    """
    Parse a SQL file that contains BEGIN_QID/END_QID markers (ora2pg output).
    Returns a dict of {qid: sql_string} with SQL collapsed to a single line.
    Comment-only lines (REVIEW_REQUIRED, WARNING) inside blocks are stripped.
    """
    result = {}
    pattern = re.compile(
        r'--\s*BEGIN_QID:(\w+)[^\n]*\n(.*?)--\s*END_QID:\1',
        re.DOTALL | re.IGNORECASE,
    )
    for match in pattern.finditer(content):
        qid      = match.group(1)
        sql_block = match.group(2)
        lines = [
            line for line in sql_block.splitlines()
            if line.strip() and not line.strip().startswith("--")
        ]
        sql = " ".join(" ".join(lines).split()).rstrip(";").strip()
        if sql:
            result[qid] = sql
    return result

def to_single_line(sql: str) -> str:
    if not sql:
        return sql
    return " ".join(sql.split()).rstrip(";").strip()

def format_sql(sql: str) -> str:
    if not sql:
        return sql
    return sqlparse.format(
        sql,
        reindent=True,
        keyword_case="upper",
        identifier_case="lower",
        strip_comments=False,
        indent_width=4,
    )

def resolve_file_path(file_field: str, src_root: str) -> str:
    if not file_field:
        return ""
    if os.path.isabs(file_field):
        return file_field
    return os.path.join(src_root, file_field)

def get_field(q: dict, key: str, default="") -> str:
    return q.get(key) or default

def extract_module(file_path: str, part_idx: int) -> str:
    if not file_path:
        return "unknown"
    parts = file_path.replace("\\", "/").split("/")
    # part_idx is 1-based; keep empty parts so the index matches the user's intuition
    # e.g. "/a/b/c" → ['', 'a', 'b', 'c'], part 2 = 'a'
    if len(parts) >= part_idx:
        return parts[part_idx - 1] or "unknown"
    return "unknown"

# ── Sidebar ────────────────────────────────────────────────────────────────────

with st.sidebar:
    st.title("⚙️ Config")
    registry_path  = st.text_input("registry.json path", "output/registry.json")
    source_root    = st.text_input("Source root (for relative paths)", "src/main/java")
    idea_cmd       = st.text_input("IntelliJ command", "idea")
    module_part_idx = st.number_input(
        "Module name: part index in file path (1-based)",
        min_value=1, max_value=20, value=7, step=1,
        help="Split the file path by '/' and take this part as the module name. "
             "e.g. index 7 on '/a/b/c/d/e/f/module/src/...' → 'module'"
    )

    if st.button("🔄 Reload from disk", use_container_width=True):
        st.session_state.pop("registry", None)
        st.rerun()

    st.divider()
    st.markdown("#### Import ora2pg output")
    st.caption(
        "Point to the SQL file ora2pg produced "
        "(BEGIN_QID/END_QID markers must be intact). "
        "Matching queries will have their **ora2pg Output** field populated automatically."
    )
    ora2pg_file_path = st.text_input("Converted SQL file path", placeholder="output/split/converted_native_sql__native_sql.sql")

    if st.button("📥 Import ora2pg conversions", use_container_width=True, type="primary"):
        if not ora2pg_file_path or not os.path.exists(ora2pg_file_path):
            st.error(f"File not found: {ora2pg_file_path}")
        else:
            content    = Path(ora2pg_file_path).read_text(encoding="utf-8")
            parsed     = parse_converted_sql_file(content)
            registry   = st.session_state.get("registry", [])
            matched    = 0
            for q in registry:
                if q["id"] in parsed:
                    q["ora2pgSql"] = parsed[q["id"]]
                    matched += 1
            save_registry(registry, registry_path)
            st.session_state.registry = registry
            st.success(f"Imported {matched} / {len(parsed)} queries found in file.")
            st.rerun()

    st.divider()
    st.markdown("#### Export converted SQL")
    st.caption(
        "Generates a SQL file with BEGIN_QID/END_QID markers from all queries "
        "that have a **Final SQL** set. Pass this file to the Java `replace` command.\n\n"
        "Partial queries (containing `<<UNRESOLVED>>`) are included — "
        "the replace command will fix only the resolved segments."
    )
    export_path = st.text_input("Export file path", value="output/converted_from_review.sql")

    if st.button("📤 Export converted SQL", use_container_width=True):
        registry = st.session_state.get("registry", [])
        exportable = [q for q in registry if q.get("convertedSql")]
        if not exportable:
            st.warning("No queries with Final SQL set yet.")
        else:
            lines = []
            for q in exportable:
                file_short = Path(q.get("file", "unknown")).name
                lines.append(
                    f"-- BEGIN_QID:{q['id']} | file:{file_short} | "
                    f"method:{q.get('method','')} | line:{q.get('line','')} | "
                    f"api:{q.get('queryType','')} | lang:{q.get('queryLanguage','')} | "
                    f"hash:{q.get('hash','')}"
                )
                lines.append(q["convertedSql"] + ";")
                lines.append(f"-- END_QID:{q['id']}")
                lines.append("")
            Path(export_path).parent.mkdir(parents=True, exist_ok=True)
            Path(export_path).write_text("\n".join(lines), encoding="utf-8")
            st.success(f"Exported {len(exportable)} queries to `{export_path}`")

    st.divider()
    st.caption(
        "Set up the IntelliJ CLI launcher:\n"
        "**Tools → Create Command-line Launcher**\n\n"
        "Then `idea` will be available in PATH."
    )

# ── Load data ──────────────────────────────────────────────────────────────────

if not os.path.exists(registry_path):
    st.error(f"File not found: **{registry_path}**  \nAdjust the path in the sidebar.")
    st.stop()

if "registry" not in st.session_state:
    st.session_state.registry = load_registry(registry_path)

queries: list = st.session_state.registry

# ── Header stats ───────────────────────────────────────────────────────────────

total        = len(queries)
needs_review = sum(1 for q in queries if q.get("needsManualReview"))
converted    = sum(1 for q in queries if q.get("convertedSql"))
reviewed     = sum(1 for q in queries if q.get("reviewStatus") and q["reviewStatus"] not in ("", "pending", "in progress"))

st.title("🔍 SQL Query Inspector")

c1, c2, c3, c4 = st.columns(4)
c1.metric("Total Queries",   total)
c2.metric("Needs Review",    needs_review)
c3.metric("Converted",       converted)
c4.metric("Review Complete", reviewed)

# ── Chart ──────────────────────────────────────────────────────────────────────

combo_counts: dict[str, int] = {}
for q in queries:
    key = f"{q.get('queryType', '?')} / {q.get('queryLanguage', '?')}"
    combo_counts[key] = combo_counts.get(key, 0) + 1

if combo_counts:
    df_chart = pd.DataFrame({
        "Combination": list(combo_counts.keys()),
        "Count":       list(combo_counts.values()),
    })
    fig = px.bar(
        df_chart, x="Combination", y="Count",
        title="Queries by API × Language",
        color="Combination",
        text="Count",
    )
    fig.update_traces(textposition="outside")
    fig.update_layout(showlegend=False, height=300, margin=dict(t=40, b=0))
    st.plotly_chart(fig, use_container_width=True)

st.divider()

# ── Filters ────────────────────────────────────────────────────────────────────

fc1, fc2, fc3, fc4, fc5, fc6 = st.columns(6)

api_options    = ["All"] + sorted({q.get("queryType",     "") for q in queries} - {""})
lang_options   = ["All"] + sorted({q.get("queryLanguage", "") for q in queries} - {""})
status_options = ["All"] + [s for s in REVIEW_STATUSES if s]

f_api       = fc1.selectbox("API Type",          api_options)
f_lang      = fc2.selectbox("Language",          lang_options)
f_status    = fc3.selectbox("Review Status",     status_options)
f_review    = fc4.selectbox("Oracle Review",     ["All", "Needs review", "OK"])
f_converted = fc5.selectbox("Converted",         ["All", "Yes", "No"])
f_oracle_c  = fc6.selectbox("Oracle Constructs", ["All", "Has constructs", "None"])

module_options = ["All"] + sorted({
    extract_module(q.get("file", ""), module_part_idx) for q in queries
} - {"unknown", ""})
f_module = st.selectbox("Module", module_options)

f_search = st.text_input("🔎 Search  (ID, file, method, reviewer, or SQL keyword)")

filtered = queries

if f_api != "All":
    filtered = [q for q in filtered if q.get("queryType") == f_api]
if f_lang != "All":
    filtered = [q for q in filtered if q.get("queryLanguage") == f_lang]
if f_status != "All":
    filtered = [q for q in filtered if q.get("reviewStatus") == f_status]
if f_review == "Needs review":
    filtered = [q for q in filtered if q.get("needsManualReview")]
elif f_review == "OK":
    filtered = [q for q in filtered if not q.get("needsManualReview")]
if f_converted == "Yes":
    filtered = [q for q in filtered if q.get("convertedSql")]
elif f_converted == "No":
    filtered = [q for q in filtered if not q.get("convertedSql")]
if f_oracle_c == "Has constructs":
    filtered = [q for q in filtered if q.get("oracleConstructs")]
elif f_oracle_c == "None":
    filtered = [q for q in filtered if not q.get("oracleConstructs")]
if f_module != "All":
    filtered = [q for q in filtered if extract_module(q.get("file", ""), module_part_idx) == f_module]
if f_search:
    s = f_search.lower()
    filtered = [
        q for q in filtered if
        s in get_field(q, "id").lower()        or
        s in get_field(q, "file").lower()       or
        s in get_field(q, "method").lower()     or
        s in get_field(q, "reviewedBy").lower() or
        s in get_field(q, "resolvedSql").lower()
    ]

st.caption(f"Showing {len(filtered)} / {total} queries")

# ── Table ──────────────────────────────────────────────────────────────────────

df_table = pd.DataFrame([{
    "ID":            q["id"],
    "File":          Path(q.get("file", "")).name,
    "Method":        q.get("method", ""),
    "Line":          q.get("line", 0),
    "API":           q.get("queryType", ""),
    "Lang":          q.get("queryLanguage", ""),
    "Oracle Review": "⚠️" if q.get("needsManualReview") else "✅",
    "ora2pg":        "✓"  if q.get("ora2pgSql")         else "",
    "Converted":     "✓"  if q.get("convertedSql")      else "",
    "Review Status": q.get("reviewStatus", ""),
    "Reviewed By":   q.get("reviewedBy", ""),
} for q in filtered])

selection_event = st.dataframe(
    df_table,
    use_container_width=True,
    hide_index=True,
    selection_mode="single-row",
    on_select="rerun",
    column_config={
        "Line":          st.column_config.NumberColumn(width="small"),
        "Oracle Review": st.column_config.TextColumn(width="small"),
        "ora2pg":        st.column_config.TextColumn(width="small"),
        "Converted":     st.column_config.TextColumn(width="small"),
        "Review Status": st.column_config.TextColumn(width="large"),
        "Reviewed By":   st.column_config.TextColumn(width="medium"),
    },
)

# ── Detail panel ───────────────────────────────────────────────────────────────

selected_rows = (
    selection_event.selection.rows
    if selection_event and selection_event.selection
    else []
)

if not selected_rows:
    st.info("Select a row above to inspect the query.")
    st.stop()

q = filtered[selected_rows[0]]

st.divider()

API_TYPES  = ["HQL", "NATIVE_SQL", "ANNOTATION", "JDBC"]
LANG_TYPES = ["HQL", "NATIVE_SQL", "AMBIGUOUS"]

# Title + API/Lang correction + review fields + oracle toggle + IntelliJ button
title_col, api_col, lang_col, status_col, reviewer_col, oracle_col, btn_col = st.columns([2, 1, 1, 2, 2, 1, 1])

title_col.subheader(f"Query  {q['id']}")

current_api  = get_field(q, "queryType")
current_lang = get_field(q, "queryLanguage")

new_api = api_col.selectbox(
    "API Type",
    API_TYPES,
    index=API_TYPES.index(current_api) if current_api in API_TYPES else 0,
    key=f"api_{q['id']}",
)

new_lang = lang_col.selectbox(
    "Language",
    LANG_TYPES,
    index=LANG_TYPES.index(current_lang) if current_lang in LANG_TYPES else 0,
    key=f"lang_{q['id']}",
)

if new_api != current_api or new_lang != current_lang:
    st.warning(
        f"Detected: `api:{current_api}` / `lang:{current_lang}` → "
        f"Corrected: `api:{new_api}` / `lang:{new_lang}` *(save to apply)*"
    )

current_status = get_field(q, "reviewStatus")
status_index   = REVIEW_STATUSES.index(current_status) if current_status in REVIEW_STATUSES else 0
new_status = status_col.selectbox(
    "Review Status",
    REVIEW_STATUSES,
    index=status_index,
    key=f"status_{q['id']}",
)

new_reviewer = reviewer_col.text_input(
    "Reviewed By",
    value=get_field(q, "reviewedBy"),
    key=f"reviewer_{q['id']}",
)

with oracle_col:
    st.markdown("**Oracle flag**")
    new_oracle = st.toggle(
        "Needs review",
        value=bool(q.get("needsManualReview")),
        key=f"oracle_{q['id']}",
    )

with btn_col:
    full_path = resolve_file_path(q.get("file", ""), source_root)
    line_no   = str(q.get("line", 1))
    if st.button("🚀 Open in IntelliJ", type="primary", use_container_width=True):
        try:
            subprocess.Popen([idea_cmd, "--line", line_no, full_path])
            st.toast(f"Opened {Path(full_path).name}:{line_no}")
        except FileNotFoundError:
            st.error(
                f"Command not found: `{idea_cmd}`  \n"
                "Set up via **Tools → Create Command-line Launcher** in IntelliJ."
            )

# Metadata expander
with st.expander("Metadata", expanded=True):
    r1c1, r1c2, r1c3, r1c4 = st.columns(4)
    r1c1.markdown(f"**File**  \n`{q.get('file', '')}`")
    r1c2.markdown(f"**Class**  \n`{q.get('className', '')}`")
    r1c3.markdown(f"**Method**  \n`{q.get('method', '')}`")
    r1c4.markdown(f"**Line**  \n`{q.get('line', '')}`")

    r2c1, r2c2 = st.columns(2)
    r2c1.markdown(f"**Resolution**  \n`{q.get('resolutionStatus', '')}`")
    r2c2.markdown(f"**Hash**  \n`{q.get('hash', '')}`")

    if q.get("oracleConstructs"):
        st.markdown(f"**Oracle constructs:** `{'`,  `'.join(q['oracleConstructs'])}`")

st.divider()

# ── Three SQL panels ───────────────────────────────────────────────────────────

col_orig, col_ora2pg, col_final = st.columns(3)

with col_orig:
    st.markdown("**① Original SQL** *(extracted from Java)*")
    st.code(format_sql(get_field(q, "resolvedSql", "(empty / unresolved)")), language="sql")

with col_ora2pg:
    st.markdown("**② ora2pg Output** *(auto-populated on import)*")
    ora2pg_val = st.text_area(
        label="ora2pg_output",
        value=format_sql(get_field(q, "ora2pgSql")),
        height=280,
        placeholder="Import a converted SQL file from the sidebar to populate this automatically…",
        label_visibility="collapsed",
        key=f"ora2pg_{q['id']}",
    )

with col_final:
    st.markdown("**③ Final SQL** *(saved to registry — used by replace command)*")

    if st.button("⬅️ Accept ora2pg as final", use_container_width=True, key=f"accept_{q['id']}"):
        st.session_state[f"final_sql_{q['id']}"] = ora2pg_val

    final_default = st.session_state.get(
        f"final_sql_{q['id']}",
        format_sql(get_field(q, "convertedSql")),
    )
    final_val = st.text_area(
        label="final_sql",
        value=final_default,
        height=230,
        placeholder="Final PostgreSQL SQL after review…",
        label_visibility="collapsed",
        key=f"final_{q['id']}",
    )

# ── Save ───────────────────────────────────────────────────────────────────────

st.divider()
save_col, _ = st.columns([1, 4])

if save_col.button("💾 Save", type="primary", use_container_width=True, key=f"save_{q['id']}"):
    for orig in queries:
        if orig["id"] == q["id"]:
            orig["queryType"]         = new_api
            orig["queryLanguage"]     = new_lang
            orig["needsManualReview"] = new_oracle
            orig["ora2pgSql"]         = to_single_line(ora2pg_val) or None
            orig["convertedSql"] = to_single_line(final_val)  or None
            orig["reviewStatus"] = new_status or None
            orig["reviewedBy"]   = new_reviewer.strip() or None
            break
    save_registry(queries, registry_path)
    st.session_state.registry = queries
    st.toast(f"Saved {q['id']}", icon="✅")
    st.rerun()
