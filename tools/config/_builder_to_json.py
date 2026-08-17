#!/usr/bin/env python3
"""
Convert a hand-written *MapBuilder.java to a JSON config for the new
mapgen pipeline. Run with:

    python _convert_builder.py <builder.java> <map_id> <display_name> <setting> <mode> <output.json>

This version understands the build() method structure: it parses the
sequence of beginSubmesh / method-call / endSubmesh blocks, then for
each block expands the called methods' addBox calls.

Handles simple for-loop expansion of the common
`for (int i = 0; i < N; i++)` pattern with a final float assignment
inside the loop body.
"""
import json
import os
import re
import sys
import hashlib
from datetime import datetime
from pathlib import Path


def extract_constants(java_path):
    text = Path(java_path).read_text()
    pattern = re.compile(
        r"public\s+static\s+final\s+(?:int|float)\s+(\w+)\s*=\s*([^;]+);"
    )
    consts = {}
    for m in pattern.finditer(text):
        name = m.group(1)
        value_str = m.group(2).strip()
        value_str = value_str.rstrip("fF")
        try:
            consts[name] = float(value_str) if "." in value_str else int(value_str)
        except ValueError:
            pass
    return consts


def try_resolve(expr, consts, locals_dict=None):
    if locals_dict is None:
        locals_dict = {}
    expr = expr.strip()
    # Only strip a trailing f/F if it's preceded by a digit (so we don't
    # butcher identifiers like "BUTTE_HALF").
    if expr and (expr.endswith("f") or expr.endswith("F")) and len(expr) > 1 and expr[-2].isdigit():
        expr = expr[:-1]
    # Strip outer parens
    while expr.startswith("(") and expr.endswith(")") and balanced_parens(expr):
        expr = expr[1:-1].strip()
    try:
        return float(expr)
    except ValueError:
        pass
    if expr in consts:
        return float(consts[expr])
    if expr in locals_dict:
        val = locals_dict[expr]
        if isinstance(val, (int, float)):
            return float(val)
    # Array indexing: NAME[INDEX]
    m_arr = re.match(r"^(\w+)\[(\d+)\]$", expr)
    if m_arr:
        name = m_arr.group(1)
        idx = int(m_arr.group(2))
        if name in locals_dict:
            arr = locals_dict[name]
            if isinstance(arr, list) and idx < len(arr):
                v = arr[idx]
                if isinstance(v, (int, float)):
                    return float(v)
    # Java-style casts: (float) expr, (int) expr
    m_cast = re.match(r"^\(\s*(?:float|int|double|long)\s*\)\s*(.+)$", expr)
    if m_cast:
        v = try_resolve(m_cast.group(1).strip(), consts, locals_dict)
        if v is not None:
            return float(v)
    # Compute the paren depth at each character position once. This is
    # what makes "split on the rightmost operator at depth 0" correct: a
    # binary operator at depth != 0 is inside a paren group and is not
    # the top-level operator we're looking for.
    depth_at = [0] * len(expr)
    depth = 0
    for i, c in enumerate(expr):
        depth_at[i] = depth
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
    # Operator precedence: + and - are LOWER than * and /, so we must
    # split on a + or - before splitting on a * or /. The previous
    # version tried * first and then +, which computed A + B * C as
    # (A + B) * C -- the inverse of the correct precedence. Splitting
    # on the RIGHTMOST operator of the chosen class (rather than the
    # leftmost) gives correct left-associativity for chains of the same
    # operator (e.g. A - B - C correctly evaluates as (A - B) - C).
    for op in ("+", "-"):
        last_split = -1
        for i, c in enumerate(expr):
            if c == op and depth_at[i] == 0 and i > 0:
                # A leading - is unary, not binary. Also unary: - right
                # after a paren or after another operator at depth 0.
                if c == "-":
                    if i == 0:
                        continue
                    prev = expr[i - 1]
                    if prev in "(+-*/":
                        continue
                last_split = i
        if last_split > 0:
            left = try_resolve(expr[:last_split], consts, locals_dict)
            right = try_resolve(expr[last_split + 1:], consts, locals_dict)
            if left is not None and right is not None:
                return left + right if op == "+" else left - right
    for op in ("*", "/"):
        last_split = -1
        for i, c in enumerate(expr):
            if c == op and depth_at[i] == 0 and i > 0:
                last_split = i
        if last_split > 0:
            left = try_resolve(expr[:last_split], consts, locals_dict)
            right = try_resolve(expr[last_split + 1:], consts, locals_dict)
            if left is not None and right is not None:
                return left * right if op == "*" else left / right
    # No binary operator found. Unary +/- on the leading sign.
    if expr.startswith("-"):
        v = try_resolve(expr[1:], consts, locals_dict)
        if v is not None:
            return -v
    if expr.startswith("+"):
        v = try_resolve(expr[1:], consts, locals_dict)
        if v is not None:
            return v
    return None


def balanced_parens(s):
    """Return True if s has balanced parens (and is not empty)."""
    depth = 0
    for c in s:
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        if depth < 0:
            return False
    return depth == 0


def find_matching_brace(text, open_pos):
    assert text[open_pos] == "{"
    depth = 0
    i = open_pos
    while i < len(text):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        elif c == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
        elif c == "/" and i + 1 < len(text) and text[i + 1] == "/":
            while i < len(text) and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < len(text) and text[i + 1] == "*":
            i += 2
            while i + 1 < len(text) and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 1
        i += 1
    return len(text)


def extract_build_method(text):
    """Return the body text of the public static byte[] build(Path) method."""
    m = re.search(
        r"public\s+static\s+byte\[\]\s+build\s*\(\s*final\s+Path\s+\w+\s*\)\s*\{",
        text,
    )
    if not m:
        # Fall back to no-arg build
        m = re.search(
            r"public\s+static\s+byte\[\]\s+build\s*\(\s*\)\s*\{",
            text,
        )
    if not m:
        return None
    body_start = m.end() - 1
    body_end = find_matching_brace(text, body_start)
    return text[body_start + 1:body_end - 1]


def extract_method_body(text, method_name):
    """Return the body of the named method, or None. Matches both
    `void methodName(final ModelBuilder ...)` and
    `void methodName(final ModelBuilder ..., float x, ...)`."""
    pattern = re.compile(
        r"(?:private|static|public|protected)?\s*(?:static\s+)?void\s+"
        + re.escape(method_name)
        + r"\s*\([^)]*\)\s*\{"
    )
    m = pattern.search(text)
    if not m:
        return None
    body_start = m.end() - 1
    body_end = find_matching_brace(text, body_start)
    return text[body_start + 1:body_end - 1]


def extract_method_params(text, method_name):
    """Return the list of parameter names for the named method, or None."""
    pattern = re.compile(
        r"(?:private|static|public|protected)?\s*(?:static\s+)?void\s+"
        + re.escape(method_name)
        + r"\s*\(([^)]*)\)"
    )
    m = pattern.search(text)
    if not m:
        return None
    params_str = m.group(1)
    # Split on commas (at depth 0)
    depth = 0
    start = 0
    parts = []
    for i, c in enumerate(params_str):
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(params_str[start:i].strip())
            start = i + 1
    parts.append(params_str[start:].strip())
    # Each part is "final float name" or "final int name" - extract the name
    names = []
    for p in parts:
        m2 = re.search(r"final\s+(?:int|float)\s+(\w+)", p)
        if m2:
            names.append(m2.group(1))
        else:
            m2 = re.search(r"(\w+)\s*$", p)
            if m2:
                names.append(m2.group(1))
    return names


def extract_geometry(java_path):
    text = Path(java_path).read_text()
    consts = extract_constants(java_path)

    # Map texture variable names to swatch names
    add_texture_pattern = re.compile(
        r'final\s+int\s+(\w+)\s*=\s*builder\.addTexture\("([\w\-]+)"'
    )
    texture_vars = {}
    for m in add_texture_pattern.finditer(text):
        var_name = m.group(1)
        tex_name = m.group(2)
        for swatch in ("floor", "wall", "accent", "accentRed",
                       "accentOrange", "crate", "column"):
            if tex_name.endswith(swatch) or tex_name == swatch:
                texture_vars[var_name] = swatch
                break

    # Parse the build() method to find the sequence of:
    #   beginSubmesh(<texture var>)
    #   [inline addBox calls + methodCall(builder);]
    #   endSubmesh()
    # We split the build body into submesh sections, then process each
    # section both for inline addBox calls and for method bodies to expand.
    build_body = extract_build_method(text)
    if not build_body:
        return [], ["no build() method found"]

    begin_sub_pattern = re.compile(r"beginSubmesh\(\s*(\w+)\s*\)")
    end_sub_pattern = re.compile(r"endSubmesh\s*\(\s*\)\s*;")
    # Method call pattern: optionally takes more args after builder
    method_call_pattern = re.compile(
        r"(\w+)\s*\(\s*builder\s*((?:,\s*[^)]+)?)\s*\)\s*;"
    )

    submesh_sections = []  # list of (swatch, body_text)
    current_swatch = None
    section_start = 0
    pos = 0
    while pos < len(build_body):
        m_sub = begin_sub_pattern.search(build_body, pos)
        m_end = end_sub_pattern.search(build_body, pos)
        if m_sub and (not m_end or m_sub.start() < m_end.start()):
            if current_swatch is not None:
                submesh_sections.append(
                    (current_swatch, build_body[section_start:m_sub.start()])
                )
            var = m_sub.group(1)
            current_swatch = texture_vars.get(var)
            section_start = m_sub.end()
            pos = section_start
        elif m_end:
            if current_swatch is not None:
                submesh_sections.append(
                    (current_swatch, build_body[section_start:m_end.start()])
                )
                current_swatch = None
                section_start = m_end.end()
            pos = m_end.end()
        else:
            break
    if current_swatch is not None:
        submesh_sections.append(
            (current_swatch, build_body[section_start:])
        )

    def get_method_calls_in_body(body_text):
        """Return list of (method_name, [arg_exprs]) for each call."""
        result = []
        for m in method_call_pattern.finditer(body_text):
            args_str = m.group(2).strip()
            args = []
            if args_str:
                # Strip leading comma
                args_str = args_str.lstrip(",").strip()
                # Split args by comma (at depth 0)
                depth = 0
                start = 0
                for i, c in enumerate(args_str):
                    if c == "(":
                        depth += 1
                    elif c == ")":
                        depth -= 1
                    elif c == "," and depth == 0:
                        args.append(args_str[start:i].strip())
                        start = i + 1
                args.append(args_str[start:].strip())
            result.append((m.group(1), args))
        return result

    # Now, for each submesh section, collect the addBox calls from the
    # methods called in that section.
    add_box_pattern = re.compile(
        r"addBox\(\s*builder\s*,\s*(.*?)\s*\)", re.DOTALL
    )
    for_loop_pattern = re.compile(
        r"for\s*\(\s*int\s+(\w+)\s*=\s*([^;]+);\s*(\w+)\s*(<|<=|>|>=)\s*([^;]+);\s*"
        r"(\w+)\s*(?:\+\+|\-\-|\+=\s*([^;)]+)|\*=\s*([^;)]+)|\-=\s*([^;)]+))"
        r"\s*\)\s*\{",
        re.DOTALL
    )
    # for-each: for (final float[] VAR : ARRAY)
    foreach_pattern = re.compile(
        r"for\s*\(\s*final\s+(?:float|int)\s*\[\s*\]\s+(\w+)\s*:\s*(\w+)\s*\)"
        r"\s*\{"
    )
    # Array literal initializer: float[][] NAME = { {...}, {...} };
    array_lit_pattern = re.compile(
        r"(?:final\s+)?(?:float|int|double|long)\s*\[\s*\]\s*\[\s*\]\s+(\w+)\s*=\s*\{(.*?)\}\s*;",
        re.DOTALL
    )
    local_assign_pattern = re.compile(
        r"final\s+float\s+(\w+)\s*=\s*([^;]+);"
    )

    results = []
    skipped = []
    submesh_index_map = {}

    def process_segment(seg_text, base_locals, current_swatch, current_submesh):
        seg_pos = 0
        iters = 0
        while seg_pos < len(seg_text):
            iters += 1
            if iters > 100000:
                break
            m_box = add_box_pattern.search(seg_text, seg_pos)
            m_for = for_loop_pattern.search(seg_text, seg_pos)
            m_foreach = foreach_pattern.search(seg_text, seg_pos)
            m_arraylit = None
            for m in array_lit_pattern.finditer(seg_text, seg_pos):
                if m_arraylit is None or m.start() < m_arraylit.start():
                    m_arraylit = m
            m_loc = None
            for m in local_assign_pattern.finditer(seg_text, seg_pos):
                if m_loc is None or m.start() < m_loc.start():
                    m_loc = m

            candidates = []
            if m_box:
                candidates.append(("box", m_box.start(), m_box))
            if m_for:
                candidates.append(("for", m_for.start(), m_for))
            if m_foreach:
                candidates.append(("foreach", m_foreach.start(), m_foreach))
            if m_arraylit:
                candidates.append(("arraylit", m_arraylit.start(), m_arraylit))
            if m_loc:
                candidates.append(("loc", m_loc.start(), m_loc))
            if not candidates:
                break
            candidates.sort(key=lambda c: c[1])
            kind, _, m = candidates[0]

            if kind == "loc":
                name = m.group(1)
                val = try_resolve(m.group(2).strip(), consts, base_locals)
                if val is not None:
                    base_locals[name] = val
                seg_pos = m.end()
            elif kind == "box":
                args_str = m.group(1)
                args = [a.strip() for a in args_str.split(",")]
                if len(args) == 6:
                    resolved = []
                    ok = True
                    for a in args:
                        v = try_resolve(a, consts, base_locals)
                        if v is None:
                            ok = False
                            skipped.append(a)
                            break
                        resolved.append(v)
                    if ok:
                        minx, miny, minz, maxx, maxy, maxz = resolved
                        x = min(minx, maxx)
                        y = min(miny, maxy)
                        z = min(minz, maxz)
                        sx = abs(maxx - minx)
                        sy = abs(maxy - miny)
                        sz = abs(maxz - minz)
                        results.append((current_submesh, current_swatch,
                                        x, y, z, sx, sy, sz))
                seg_pos = m.end()
            elif kind == "for":
                iter_var = m.group(1)
                start_expr = m.group(2).strip()
                comp_var = m.group(3)
                comp_op = m.group(4)
                end_expr = m.group(5).strip()
                step_var = m.group(6)
                if m.group(7):
                    step_op = "+"
                    step_expr = m.group(7).strip()
                elif m.group(8):
                    step_op = "*"
                    step_expr = m.group(8).strip()
                elif m.group(9):
                    step_op = "-"
                    step_expr = m.group(9).strip()
                else:
                    step_op = "+"
                    step_expr = "1"

                if comp_var != iter_var or step_var != iter_var:
                    seg_pos = m.end()
                    continue

                body_start = m.end() - 1
                body_end = find_matching_brace(seg_text, body_start)
                body_text = seg_text[body_start + 1:body_end - 1]

                start_val = try_resolve(start_expr, consts, base_locals)
                end_val = try_resolve(end_expr, consts, base_locals)
                step_val = try_resolve(step_expr, consts, base_locals)

                if start_val is None or end_val is None or step_val is None:
                    seg_pos = body_end
                    continue
                if step_val == 0:
                    seg_pos = body_end
                    continue

                i = start_val
                count = 0
                while count < 10000:
                    cond = (
                        (i < end_val) if comp_op == "<" else
                        (i <= end_val) if comp_op == "<=" else
                        (i > end_val) if comp_op == ">" else
                        (i >= end_val)
                    )
                    if not cond:
                        break
                    iter_locals = dict(base_locals)
                    iter_locals[iter_var] = i
                    process_segment(body_text, iter_locals,
                                    current_swatch, current_submesh)
                    if step_op == "+":
                        i = i + step_val
                    elif step_op == "-":
                        i = i - step_val
                    elif step_op == "*":
                        i = i * step_val
                    count += 1

                seg_pos = body_end
            elif kind == "arraylit":
                # Record the array literal in base_locals
                name = m.group(1)
                items_str = m.group(2)
                inner_arrays = re.findall(r"\{([^}]*)\}", items_str)
                arrays = []
                for arr in inner_arrays:
                    vals = []
                    for x in arr.split(","):
                        x = x.strip()
                        if x:
                            v = try_resolve(x, consts, base_locals)
                            vals.append(v if v is not None else float("nan"))
                    arrays.append(vals)
                base_locals[name] = arrays
                seg_pos = m.end()
            elif kind == "foreach":
                iter_var = m.group(1)
                array_name = m.group(2)
                arrays = base_locals.get(array_name)
                if not arrays or not isinstance(arrays, list):
                    body_start = m.end() - 1
                    body_end = find_matching_brace(seg_text, body_start)
                    seg_pos = body_end
                    continue
                body_start = m.end() - 1
                body_end = find_matching_brace(seg_text, body_start)
                body_text = seg_text[body_start + 1:body_end - 1]
                for arr_vals in arrays:
                    iter_locals = dict(base_locals)
                    iter_locals[iter_var] = arr_vals
                    process_segment(body_text, iter_locals,
                                    current_swatch, current_submesh)
                seg_pos = body_end

    for swatch, body_text in submesh_sections:
        if swatch not in submesh_index_map:
            submesh_index_map[swatch] = len(submesh_index_map)
        submesh_idx = submesh_index_map[swatch]
        # 1) Process the body text for inline addBox calls and locals
        process_segment(body_text, dict(consts), swatch, submesh_idx)
        # 2) Find any methodCall(builder, ...); calls and expand them
        for method_name, arg_exprs in get_method_calls_in_body(body_text):
            called_body = extract_method_body(text, method_name)
            if called_body is None:
                skipped.append(f"method {method_name} not found")
                continue
            # Bind call args to method parameters
            params = extract_method_params(text, method_name)
            method_locals = dict(consts)
            if params:
                for i, p in enumerate(params):
                    if i == 0:
                        # The first param is `final ModelBuilder builder`,
                        # which is not a float - skip it.
                        continue
                    arg_idx = i - 1
                    if arg_idx < len(arg_exprs):
                        v = try_resolve(arg_exprs[arg_idx], consts, method_locals)
                        if v is not None:
                            method_locals[p] = v
            # Recurse: process the called method's body, but also expand
            # any method calls inside that body.
            process_segment(called_body, method_locals, swatch, submesh_idx)
            # 3) Recurse into the called method's body for any further
            # method calls (e.g., addCacti -> addCactusAt)
            for inner_name, inner_args in get_method_calls_in_body(called_body):
                inner_body = extract_method_body(text, inner_name)
                if inner_body is None:
                    skipped.append(f"method {inner_name} not found")
                    continue
                inner_params = extract_method_params(text, inner_name)
                inner_locals = dict(method_locals)
                if inner_params:
                    for i, p in enumerate(inner_params):
                        if i == 0:
                            continue
                        arg_idx = i - 1
                        if arg_idx < len(inner_args):
                            v = try_resolve(inner_args[arg_idx], consts, inner_locals)
                            if v is not None:
                                inner_locals[p] = v
                process_segment(inner_body, inner_locals, swatch, submesh_idx)

    return results, skipped


def main():
    if len(sys.argv) != 7:
        print(__doc__)
        sys.exit(1)
    java_path = sys.argv[1]
    map_id = sys.argv[2]
    display_name = sys.argv[3]
    setting = sys.argv[4]
    mode = sys.argv[5]
    output_path = sys.argv[6]

    # Audit log: same file the Java BuildAudit writes to. The Python
    # converter is build-time only (nothing here ships at runtime), so
    # the path is hard-coded relative to the Gradle root. Override with
    # AUDIT_LOG_FILE if needed.
    audit_log = Path(os.environ.get("AUDIT_LOG_FILE",
        "tools/build/logs/build-audit.log"))

    def audit(level, msg):
        try:
            audit_log.parent.mkdir(parents=True, exist_ok=True)
            ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
            with audit_log.open("a", encoding="utf-8") as f:
                f.write(f"{ts} {level:<5} python-script com.openfps.tools.mapgen.BuildAudit - {msg}\n")
        except OSError:
            pass  # Audit logging is best-effort; never break a build over it.

    audit("INFO", f"BEGIN mapId={map_id} source=script:_builder_to_json.py"
        f" java={Path(java_path).name} output={output_path}")

    geometry, skipped = extract_geometry(java_path)
    if not geometry:
        audit("ERROR", f"FAIL mapId={map_id} no geometry found in {java_path}")
        print(f"No geometry found in {java_path}", file=sys.stderr)
        sys.exit(1)
    if skipped:
        audit("WARN", f"mapId={map_id} skipped expressions: {skipped}")
        print(f"Skipped: {skipped}", file=sys.stderr)

    primitives = []
    submesh_counts = {}
    texture_counts = {}
    for submesh, swatch, x, y, z, sx, sy, sz in geometry:
        primitives.append({
            "type": "box",
            "x": x,
            "y": y,
            "z": z,
            "sx": sx,
            "sy": sy,
            "sz": sz,
            "submesh": submesh,
            "texture": swatch,
        })
        submesh_counts[submesh] = submesh_counts.get(submesh, 0) + 1
        texture_counts[swatch] = texture_counts.get(swatch, 0) + 1

    config = {
        "id": map_id,
        "displayName": display_name,
        "setting": setting,
        "mode": mode,
        "textureEdge": 64,
        "worldUnitsPerTile": 8.0,
        "primitives": primitives,
    }

    out = Path(output_path)
    out.write_text(json.dumps(config, indent=2) + "\n")

    sha = hashlib.sha256(out.read_bytes()).hexdigest()

    audit("INFO", f"END mapId={map_id} source=script:_builder_to_json.py"
        f" json={output_path} size={out.stat().st_size}B sha256={sha}"
        f" primitives={len(primitives)} submeshes={len(submesh_counts)}"
        f" textures={len(texture_counts)} swatches={sorted(texture_counts)}")

    print(f"Wrote {len(primitives)} primitives to {output_path}")


if __name__ == "__main__":
    main()
