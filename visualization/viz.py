import json
import os
import subprocess
from pathlib import Path

import pandas as pd
import plotly.express as px
import streamlit as st

st.set_page_config(page_title="SQL Query Inspector", page_icon="🔍", layout="wide")

# ── Sidebar ────────────────────────────────────────────────────────────────────

with st.sidebar:
    st.title("⚙️ Config")
    registry_path = st.text_input("registry.json path", "output/registry.json")
    source_root   = st.text_input("Source root (for relative paths)", "src/main/java")
    idea_cmd      = st.text_input("IntelliJ command", "idea")
    if st.button("🔄 Reload from disk", use_container_width=True):
        st.session_state.pop("registry", None)
        st.rerun()
    st.divider()
    st.caption(
        "Set up the IntelliJ CLI launcher:\n"
        "**Tools → Create Command-line Launcher**\n\n"
        "Then `idea` will be available in PATH."
    )

# ── Load / save ────────────────────────────────────────────────────────────────

def load_registry(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)

def save_registry(data: dict, path: str):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

def resolve_file_path(file_field: str, source_root: str) -> str:
    if not file_field:
        return ""
    if os.path.isabs(file_field):
        return file_field
    return os.path.join(source_root, file_field)

if not os.path.exists(registry_path):
    st.error(f"File not found: **{registry_path}**  \nAdjust the path in the sidebar.")
    st.stop()

if "registry" not in st.session_state:
    st.session_state.registry = load_registry(registry_path)

data    = st.session_state.registry
queries = data.get("queries", [])

# ── Header stats ───────────────────────────────────────────────────────────────

total        = len(queries)
needs_review = sum(1 for q in queries if q.get("needsManualReview"))
converted    = sum(1 for q in queries if q.get("convertedSql"))
unresolved   = sum(1 for q in queries if q.get("resolutionStatus") != "RESOLVED")

st.title("🔍 SQL Query Inspector")

c1, c2, c3, c4 = st.columns(4)
c1.metric("Total Queries",  total)
c2.metric("Needs Review",   needs_review,  delta=None)
c3.metric("Converted",      converted,     delta=None)
c4.metric("Unresolved SQL", unresolved,    delta=None)

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
    fig.update_layout(showlegend=False, height=320, margin=dict(t=40, b=0))
    st.plotly_chart(fig, use_container_width=True)

st.divider()

# ── Filters ────────────────────────────────────────────────────────────────────

fc1, fc2, fc3, fc4 = st.columns(4)

api_options  = ["All"] + sorted({q.get("queryType",    "") for q in queries} - {""})
lang_options = ["All"] + sorted({q.get("queryLanguage","") for q in queries} - {""})

f_api       = fc1.selectbox("API Type",  api_options)
f_lang      = fc2.selectbox("Language",  lang_options)
f_review    = fc3.selectbox("Review",    ["All", "Needs review", "OK"])
f_converted = fc4.selectbox("Converted", ["All", "Yes", "No"])
f_search    = st.text_input("🔎 Search  (ID, file, method, or SQL keyword)")

filtered = queries

if f_api != "All":
    filtered = [q for q in filtered if q.get("queryType") == f_api]
if f_lang != "All":
    filtered = [q for q in filtered if q.get("queryLanguage") == f_lang]
if f_review == "Needs review":
    filtered = [q for q in filtered if q.get("needsManualReview")]
elif f_review == "OK":
    filtered = [q for q in filtered if not q.get("needsManualReview")]
if f_converted == "Yes":
    filtered = [q for q in filtered if q.get("convertedSql")]
elif f_converted == "No":
    filtered = [q for q in filtered if not q.get("convertedSql")]
if f_search:
    s = f_search.lower()
    filtered = [
        q for q in filtered if
        s in (q.get("id")          or "").lower() or
        s in (q.get("file")        or "").lower() or
        s in (q.get("method")      or "").lower() or
        s in (q.get("resolvedSql") or "").lower()
    ]

st.caption(f"Showing {len(filtered)} / {total} queries")

# ── Table ──────────────────────────────────────────────────────────────────────

df_table = pd.DataFrame([{
    "ID":        q["id"],
    "File":      Path(q.get("file", "")).name,
    "Class":     q.get("className", ""),
    "Method":    q.get("method", ""),
    "Line":      q.get("line", 0),
    "API":       q.get("queryType", ""),
    "Lang":      q.get("queryLanguage", ""),
    "Review":    "⚠️" if q.get("needsManualReview")            else "✅",
    "Converted": "✓"  if q.get("convertedSql")                 else "",
    "Status":    q.get("resolutionStatus", ""),
} for q in filtered])

selection_event = st.dataframe(
    df_table,
    use_container_width=True,
    hide_index=True,
    selection_mode="single-row",
    on_select="rerun",
    column_config={
        "Line":      st.column_config.NumberColumn(width="small"),
        "Review":    st.column_config.TextColumn(width="small"),
        "Converted": st.column_config.TextColumn(width="small"),
        "Status":    st.column_config.TextColumn(width="medium"),
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
st.subheader(f"Query  {q['id']}")

# Metadata row
meta_col, btn_col = st.columns([5, 1])

with meta_col:
    r1c1, r1c2, r1c3, r1c4 = st.columns(4)
    r1c1.markdown(f"**File**  \n`{Path(q.get('file','') ).name}`")
    r1c2.markdown(f"**Class**  \n`{q.get('className','')}`")
    r1c3.markdown(f"**Method**  \n`{q.get('method','')}`")
    r1c4.markdown(f"**Line**  \n`{q.get('line','')}`")

    r2c1, r2c2, r2c3, r2c4 = st.columns(4)
    r2c1.markdown(f"**API**  \n`{q.get('queryType','')}`")
    r2c2.markdown(f"**Lang**  \n`{q.get('queryLanguage','')}`")
    r2c3.markdown(f"**Resolution**  \n`{q.get('resolutionStatus','')}`")
    r2c4.markdown(f"**Hash**  \n`{q.get('hash','')}`")

    if q.get("oracleConstructs"):
        st.markdown(f"**Oracle constructs:** `{'`,  `'.join(q['oracleConstructs'])}`")

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
                "Set up the launcher via **Tools → Create Command-line Launcher** in IntelliJ, "
                "or update the command in the sidebar."
            )

    st.caption(f"`{full_path}`")

st.divider()

# SQL editor
left, right = st.columns(2)

with left:
    st.markdown("**Original SQL** *(read-only)*")
    st.code(q.get("resolvedSql") or "(empty / unresolved)", language="sql")

with right:
    st.markdown("**Converted SQL** *(editable — saved to registry.json)*")

    edited = st.text_area(
        label="converted_sql_editor",
        value=q.get("convertedSql") or "",
        height=260,
        placeholder="Paste the PostgreSQL-converted SQL here…",
        label_visibility="collapsed",
        key=f"editor_{q['id']}",
    )

    save_btn, clear_btn = st.columns(2)

    if save_btn.button("💾 Save", type="primary", use_container_width=True, key=f"save_{q['id']}"):
        new_val = edited.strip() or None
        for orig in data["queries"]:
            if orig["id"] == q["id"]:
                orig["convertedSql"] = new_val
                break
        save_registry(data, registry_path)
        st.session_state.registry = data
        st.toast(f"Saved {q['id']}", icon="✅")
        st.rerun()

    if clear_btn.button("🗑️ Clear", use_container_width=True, key=f"clear_{q['id']}"):
        for orig in data["queries"]:
            if orig["id"] == q["id"]:
                orig["convertedSql"] = None
                break
        save_registry(data, registry_path)
        st.session_state.registry = data
        st.toast(f"Cleared {q['id']}", icon="🗑️")
        st.rerun()
