import argparse
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import quote

from filelock import FileLock, Timeout

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
    """Full overwrite — only used for bulk operations (ora2pg import). Acquires file lock."""
    lock_path = path + ".lock"
    with FileLock(lock_path, timeout=10):
        _atomic_write(queries, path)

def save_query(query_id: str, updates: dict, local_version: int, path: str, force: bool = False) -> dict:
    """
    Concurrent-safe single-query save with optimistic locking.

    Acquires an exclusive file-level lock, reloads the registry from disk,
    checks per-query version to detect concurrent edits, then writes atomically.

    Returns a dict:
      {"status": "ok",       "registry": <list>}
      {"status": "conflict", "disk_query": <dict>, "registry": <list>}
      {"status": "timeout"}

    Backward compat: missing version field is treated as version 0.
    """
    lock_path = path + ".lock"
    try:
        with FileLock(lock_path, timeout=5):
            current = load_registry(path)
            target = next((q for q in current if q["id"] == query_id), None)
            if target is None:
                return {"status": "ok", "registry": current}

            disk_version = target.get("version", 0)
            if not force and disk_version != local_version:
                return {"status": "conflict", "disk_query": dict(target), "registry": current}

            for k, v in updates.items():
                if v is None:
                    target.pop(k, None)
                else:
                    target[k] = v
            target["version"] = disk_version + 1

            _atomic_write(current, path)
            return {"status": "ok", "registry": current}
    except Timeout:
        return {"status": "timeout"}

def _atomic_write(data: list, path: str):
    """Write JSON to a temp file then rename — prevents partial writes."""
    dir_name = os.path.dirname(os.path.abspath(path))
    with tempfile.NamedTemporaryFile(
        "w", dir=dir_name, suffix=".tmp", delete=False, encoding="utf-8"
    ) as tmp:
        json.dump(data, tmp, indent=2, ensure_ascii=False)
        tmp_path = tmp.name
    os.replace(tmp_path, path)  # atomic on Linux, macOS, Windows

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

def detect_registry_prefix(queries: list) -> str:
    """Best-guess common prefix of absolute file paths stored in the registry."""
    paths = [q.get("file", "") for q in queries if q.get("file", "").startswith("/")]
    if not paths:
        return ""
    try:
        return os.path.commonpath(paths)
    except (ValueError, TypeError):
        return ""

def resolve_file_path(file_field: str, registry_prefix: str, local_prefix: str) -> str:
    """
    Converts a registry file path to the correct local path for this user.
    If registry_prefix and local_prefix are set, replaces the leading prefix.
    Falls back to the raw value if no substitution is configured.
    """
    if not file_field:
        return ""
    if registry_prefix and local_prefix and file_field.startswith(registry_prefix):
        return local_prefix + file_field[len(registry_prefix):]
    return file_field

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

# ── CLI args ───────────────────────────────────────────────────────────────────
# Usage: streamlit run viz.py -- path/to/registry.json
#    or: streamlit run viz.py -- --registry path/to/registry.json

def _parse_cli() -> str | None:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("registry", nargs="?", default=None)
    parser.add_argument("--registry", dest="registry_flag", default=None)
    args, _ = parser.parse_known_args(sys.argv[1:])
    return args.registry_flag or args.registry

_cli_registry = _parse_cli()

# ── Sidebar ────────────────────────────────────────────────────────────────────

with st.sidebar:
    st.title("⚙️ Config")
    registry_path = st.text_input("registry.json path", _cli_registry or "output/registry.json")
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
    st.markdown("#### Open in IntelliJ")
    st.caption(
        "Uses IntelliJ's built-in HTTP server (`localhost:63342`). "
        "Each team member sets **their own** local project root below — "
        "the link opens on their machine, not the server's."
    )

    # Auto-detect the stored prefix from registry paths once data is loaded
    _auto_prefix = detect_registry_prefix(st.session_state.get("registry", []))
    registry_prefix = st.text_input(
        "Project root stored in registry",
        value=_auto_prefix,
        help="The absolute path prefix that was recorded when extract was run "
             "(auto-detected from registry paths). Leave blank to use paths as-is.",
    )
    local_prefix = st.text_input(
        "Your local project root",
        help="Replace the stored prefix with this path on your machine. "
             "e.g. /home/you/projects/MyProject",
    )
    st.markdown("**URL template** — use `{file}` and `{line}` as placeholders")
    intellij_url_template = st.text_input(
        "url_template",
        value="idea://open?file={file}&line={line}",
        label_visibility="collapsed",
        help="Use {file} and {line} as placeholders. idea:// works on macOS and on Linux after one-time setup below.",
    )

    with st.expander("🐧 Ubuntu/Linux first-time setup"):
        st.markdown(
            "Snap-installed IntelliJ does not register a browser URL handler automatically. "
            "Run these commands **once** on each Ubuntu machine to register `idea://`:"
        )
        st.code(
            """\
mkdir -p ~/.local/bin ~/.local/share/applications

# 1. Wrapper script — parses idea://open?file=...&line=... and calls IntelliJ CLI
cat > ~/.local/bin/idea-url-handler.sh << 'EOF'
#!/bin/bash
URL="$1"
FILE=$(python3 -c "import sys,urllib.parse; u='$URL'; print(urllib.parse.unquote(u.split('file=')[1].split('&')[0]))")
LINE=$(python3 -c "u='$URL'; print(u.split('line=')[1] if 'line=' in u else '1')")
intellij-idea-ultimate --line "$LINE" "$FILE" &
EOF
chmod +x ~/.local/bin/idea-url-handler.sh

# 2. .desktop file — registers the handler with the desktop environment
cat > ~/.local/share/applications/idea-url-handler.desktop << 'EOF'
[Desktop Entry]
Name=IntelliJ IDEA URL Handler
Exec=/home/$USER/.local/bin/idea-url-handler.sh %u
Terminal=false
Type=Application
MimeType=x-scheme-handler/idea;
EOF

# 3. Register idea:// with xdg
xdg-mime default idea-url-handler.desktop x-scheme-handler/idea
update-desktop-database ~/.local/share/applications/

# 4. Test — should open IntelliJ at line 1
xdg-open "idea://open?file=$HOME/.bashrc&line=1"
""",
            language="bash",
        )
        st.caption(
            "After running step 4, your browser should also handle `idea://` links. "
            "If Chrome/Firefox asks for permission the first time, click **Allow**."
        )

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
            content = Path(ora2pg_file_path).read_text(encoding="utf-8")
            parsed  = parse_converted_sql_file(content)
            # Reload from disk before bulk write to pick up any concurrent changes
            registry = load_registry(registry_path)
            matched  = 0
            for q in registry:
                if q["id"] in parsed:
                    q["ora2pgSql"] = parsed[q["id"]]
                    matched += 1
            save_registry(registry, registry_path)
            st.session_state.registry       = registry
            st.session_state.registry_mtime = os.path.getmtime(registry_path)
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

    st.caption(
        "IntelliJ built-in server must be enabled:\n"
        "**Settings → Build, Execution, Deployment → Debugger → Built-in server**\n\n"
        "It is on by default in most IntelliJ versions."
    )

# ── Load data ──────────────────────────────────────────────────────────────────

if not os.path.exists(registry_path):
    st.error(f"File not found: **{registry_path}**  \nAdjust the path in the sidebar.")
    st.stop()

_disk_mtime = os.path.getmtime(registry_path)
if (
    "registry" not in st.session_state
    or st.session_state.get("registry_path") != registry_path
    or st.session_state.get("registry_mtime", 0) < _disk_mtime
):
    st.session_state.registry       = load_registry(registry_path)
    st.session_state.registry_mtime = _disk_mtime
    st.session_state.registry_path  = registry_path

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

# ── Charts ─────────────────────────────────────────────────────────────────────

chart_col1, chart_col2 = st.columns(2)

combo_counts: dict[str, int] = {}
for q in queries:
    key = f"{q.get('queryType', '?')} / {q.get('queryLanguage', '?')}"
    combo_counts[key] = combo_counts.get(key, 0) + 1

if combo_counts:
    df_combo = pd.DataFrame({
        "Combination": list(combo_counts.keys()),
        "Count":       list(combo_counts.values()),
    })
    fig_combo = px.bar(
        df_combo, x="Combination", y="Count",
        title="Queries by API × Language",
        color="Combination", text="Count",
    )
    fig_combo.update_traces(textposition="outside")
    fig_combo.update_layout(showlegend=False, height=320, margin=dict(t=40, b=0))
    chart_col1.plotly_chart(fig_combo, use_container_width=True)

oracle_counts: dict[str, int] = {}
for q in queries:
    for c in q.get("oracleConstructs") or []:
        oracle_counts[c] = oracle_counts.get(c, 0) + 1

if oracle_counts:
    df_oracle = pd.DataFrame({
        "Construct": list(oracle_counts.keys()),
        "Queries":   list(oracle_counts.values()),
    }).sort_values("Queries", ascending=False)
    fig_oracle = px.bar(
        df_oracle, x="Construct", y="Queries",
        title="Oracle Constructs — queries affected",
        color="Queries",
        color_continuous_scale="Reds",
        text="Queries",
    )
    fig_oracle.update_traces(textposition="outside")
    fig_oracle.update_layout(showlegend=False, height=320, margin=dict(t=40, b=0),
                             coloraxis_showscale=False)
    chart_col2.plotly_chart(fig_oracle, use_container_width=True)

st.divider()

# ── Filters ────────────────────────────────────────────────────────────────────

fc1, fc2, fc3, fc4, fc5 = st.columns(5)

api_options    = ["All"] + sorted({q.get("queryType",     "") for q in queries} - {""})
lang_options   = ["All"] + sorted({q.get("queryLanguage", "") for q in queries} - {""})
status_options = ["All"] + [s for s in REVIEW_STATUSES if s]

f_api       = fc1.selectbox("API Type",      api_options)
f_lang      = fc2.selectbox("Language",      lang_options)
f_status    = fc3.selectbox("Review Status", status_options)
f_review    = fc4.selectbox("Oracle Review", ["All", "Needs review", "OK"])
f_converted = fc5.selectbox("Converted",     ["All", "Yes", "No"])

# Oracle construct multiselect — sorted by frequency so most common appear first
all_constructs = sorted(oracle_counts.keys(), key=lambda c: -oracle_counts.get(c, 0))
construct_options = [f"{c} ({oracle_counts[c]})" for c in all_constructs]
selected_construct_labels = st.multiselect(
    "Oracle Constructs  (select one or more to filter — shows queries containing ANY selected)",
    options=construct_options,
)
selected_constructs = {lbl.split(" (")[0] for lbl in selected_construct_labels}

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
if selected_constructs:
    filtered = [q for q in filtered if selected_constructs & set(q.get("oracleConstructs") or [])]
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

# Track the version we started editing so optimistic locking can detect concurrent changes.
# Set once when the query is first selected; NOT reset on mtime auto-reload so the
# stale-version check still fires if someone else saved while we were editing.
_version_key = f"editing_version_{q['id']}"
if _version_key not in st.session_state:
    st.session_state[_version_key] = q.get("version", 0)

st.divider()

API_TYPES  = ["HQL", "NATIVE_SQL", "ANNOTATION", "JDBC", "YAML"]
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

local_path = resolve_file_path(q.get("file", ""), registry_prefix, local_prefix)
line_no    = q.get("line", 1)
_encoded   = quote(local_path, safe="/")

intellij_url = intellij_url_template.replace("{file}", _encoded).replace("{line}", str(line_no))

with btn_col:
    st.link_button("🚀 Open in IntelliJ", intellij_url, use_container_width=True, type="primary")

with st.expander("🔗 IntelliJ debug", expanded=False):
    st.caption(f"**Resolved local path:** `{local_path}`")
    st.caption(f"**Stored path in registry:** `{q.get('file', '')}`")
    st.markdown("**Active URL** *(paste into browser to test)*")
    st.code(intellij_url, language=None)

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
    ora2pg_val = get_field(q, "ora2pgSql")
    if ora2pg_val:
        st.code(format_sql(ora2pg_val), language="sql")
    else:
        st.caption("Import a converted SQL file from the sidebar to populate this automatically…")

with col_final:
    st.markdown("**③ Final SQL** *(saved to registry — used by replace command)*")

    if st.button("⬅️ Accept ora2pg as final", use_container_width=True, key=f"accept_{q['id']}"):
        st.session_state[f"final_sql_{q['id']}"] = format_sql(ora2pg_val)

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

def _do_save(force: bool = False):
    updates = {
        "queryType":         new_api,
        "queryLanguage":     new_lang,
        "needsManualReview": new_oracle,
        "ora2pgSql":         to_single_line(ora2pg_val) or None,
        "convertedSql":      to_single_line(final_val)  or None,
        "reviewStatus":      new_status or None,
        "reviewedBy":        new_reviewer.strip() or None,
    }
    local_version = st.session_state.get(_version_key, 0)
    result = save_query(q["id"], updates, local_version, registry_path, force=force)

    if result["status"] == "ok":
        st.session_state.registry       = result["registry"]
        st.session_state.registry_mtime = os.path.getmtime(registry_path)
        # Update tracked version to the newly saved one
        saved_q = next((x for x in result["registry"] if x["id"] == q["id"]), None)
        if saved_q:
            st.session_state[_version_key] = saved_q.get("version", 0)
        st.session_state.pop(f"_conflict_{q['id']}", None)
        st.toast(f"Saved {q['id']}", icon="✅")
        st.rerun()
    elif result["status"] == "conflict":
        st.session_state[f"_conflict_{q['id']}"] = {
            "disk_query": result["disk_query"],
            "our_updates": updates,
        }
        st.session_state.registry       = result["registry"]
        st.session_state.registry_mtime = os.path.getmtime(registry_path)
        st.rerun()
    elif result["status"] == "timeout":
        st.error("Could not acquire file lock — another process is saving. Try again.")

if save_col.button("💾 Save", type="primary", use_container_width=True, key=f"save_{q['id']}"):
    _do_save(force=False)

# ── Conflict UI ────────────────────────────────────────────────────────────────

conflict = st.session_state.get(f"_conflict_{q['id']}")
if conflict:
    disk_q      = conflict["disk_query"]
    our_updates = conflict["our_updates"]

    st.warning(
        "⚠️ **Save conflict** — this query was modified by someone else while you were editing. "
        "Fields that differ are shown below."
    )

    FIELD_LABELS = {
        "queryType": "API Type", "queryLanguage": "Language",
        "needsManualReview": "Oracle flag", "ora2pgSql": "ora2pg Output",
        "convertedSql": "Final SQL", "reviewStatus": "Review Status",
        "reviewedBy": "Reviewed By",
    }
    diff_fields = [
        k for k in our_updates
        if str(our_updates.get(k) or "") != str(disk_q.get(k) or "")
    ]
    if diff_fields:
        col_lbl, col_disk, col_mine = st.columns([1, 2, 2])
        col_lbl.markdown("**Field**")
        col_disk.markdown("**On disk (theirs)**")
        col_mine.markdown("**Your edit**")
        for k in diff_fields:
            col_lbl.markdown(FIELD_LABELS.get(k, k))
            col_disk.code(str(disk_q.get(k) or ""))
            col_mine.code(str(our_updates.get(k) or ""))

    conflict_btn_col1, conflict_btn_col2, *_ = st.columns([1, 1, 3])
    if conflict_btn_col1.button("🗑️ Discard mine", key=f"discard_{q['id']}"):
        st.session_state.pop(f"_conflict_{q['id']}", None)
        st.session_state.pop(_version_key, None)
        st.rerun()
    if conflict_btn_col2.button("🔧 Force save (overwrite theirs)", key=f"force_{q['id']}", type="primary"):
        _do_save(force=True)
