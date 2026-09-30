#!/usr/bin/env python3
"""Checks that a Swift port declares every non-private member of its Kotlin source.

For each Kotlin file given, every top-level or member `fun`, `val`, `var`, `class`, `object` and enum/sealed case that
is not `private` must have a declaration with the same name somewhere in the Swift files given (Swift may turn a
`val` into `var`/`let`/a computed property, and a sealed subclass into an enum case). Overrides, `operator` functions,
constructors and Kotlin-only boilerplate (`toString`, `equals`, `hashCode`, `copy`, `component1`…) are ignored.

Usage: scripts/check-port-parity.py --kotlin A.kt B.kt --swift A.swift B.swift [--ignore name1,name2]
Exit status is non-zero when something is missing.
"""
import argparse
import re
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--kotlin", nargs="+", required=True)
parser.add_argument("--swift", nargs="+", required=True)
parser.add_argument("--ignore", default="", help="comma-separated names that are deliberately not ported")
args = parser.parse_args()

IGNORED = {"toString", "equals", "hashCode", "copy", "invoke", "values", "valueOf", "entries", "Companion", "TAG"}
IGNORED |= {name for name in args.ignore.split(",") if name}

decl = re.compile(
    r"^(?P<indent>\s*)(?P<mods>(?:@\S+\s+)*(?:(?:public|internal|protected|private|override|suspend|inline|operator|"
    r"infix|tailrec|data|sealed|enum|abstract|open|const|lateinit|value|annotation|fun)\s+)*)"
    r"(?P<kind>fun|val|var|class|object|interface)\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][\w.]*\.)?(?P<name>`[^`]+`|[A-Za-z_]\w*)",
    re.M,
)

wanted: dict[str, str] = {}
type_line = re.compile(r"\b(class|object|interface)\b")


def strip_literals(line: str) -> str:
    """Drops string and char literals so their braces do not count."""
    line = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
    return re.sub(r"'(?:[^'\\]|\\.)'", "''", line)


for path in args.kotlin:
    source = open(path, encoding="utf-8").read()
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r'"""[\s\S]*?"""', '""', source)
    source = re.sub(r"//[^\n]*", "", source)
    # Each entry: ("type", is_private, is_enum) for class/object/interface bodies, ("code",) for everything else.
    stack: list[tuple] = []
    # A class header can span lines (`class X @Inject constructor(` … `) {`); its body opens at the first brace after
    # the header, which must count as a type body rather than code.
    pending_type = None
    for line in source.splitlines():
        in_type_body = not stack or stack[-1][0] == "type"
        enclosing_private = any(entry[0] == "type" and entry[1] for entry in stack)
        m = decl.match(line)
        if m and in_type_body and not enclosing_private:
            mods = m.group("mods")
            name = m.group("name").strip("`")
            skip = "private" in mods or "override" in mods or "operator" in mods
            if not skip and name not in IGNORED and not re.fullmatch(r"component\d+", name):
                wanted.setdefault(name, path.split("/")[-1])
        if stack and stack[-1][0] == "type" and stack[-1][2] and not enclosing_private:
            entry = re.match(r"^\s*([A-Z][A-Z0-9_]+)\s*(?:\(|,|;|$)", line)
            if entry:
                wanted.setdefault(entry.group(1), path.split("/")[-1])
        code = strip_literals(line)
        header = re.match(r"^\s*(?:@\S+\s+)*(?:(?:public|internal|private|protected|data|sealed|enum|abstract|open|"
                          r"inner|value|annotation|companion)\s+)*(class|object|interface)\b", code)
        if header and "{" not in code:
            pending_type = ("private" in code.split(), "enum class" in code)
        elif m and not header and pending_type is not None and m.group("indent") == "":
            # A new top-level declaration: the previous header had no body.
            pending_type = None
        first = True
        for char in code:
            if char == "{":
                head = code.split("{")[0]
                if first and header and not re.search(r"\bfun\b", head):
                    stack.append(("type", "private" in head.split(), "enum class" in head))
                    pending_type = None
                elif first and pending_type is not None:
                    stack.append(("type", pending_type[0], pending_type[1]))
                    pending_type = None
                else:
                    stack.append(("code",))
                first = False
            elif char == "}" and stack:
                stack.pop()

swift = "\n".join(open(p, encoding="utf-8").read() for p in args.swift)
declared = set(re.findall(r"\b(?:func|var|let|case|class|struct|enum|actor|protocol|typealias)\s+`?([A-Za-z_]\w*)", swift))
# `case a, b, c` declares several enum cases on one line.
for line in re.findall(r"\bcase\s+([A-Za-z_][\w\s,()?:.<>\[\]]*)", swift):
    declared |= set(re.findall(r"(?:^|,)\s*([A-Za-z_]\w*)", line))


def swift_names(kotlin_name: str) -> set[str]:
    """SCREAMING_CASE constants and enum entries become lowerCamelCase in Swift."""
    names = {kotlin_name}
    if re.fullmatch(r"[A-Z][A-Z0-9_]*", kotlin_name):
        parts = kotlin_name.lower().split("_")
        names.add(parts[0] + "".join(p.capitalize() for p in parts[1:]))
    if kotlin_name[:1].isupper():
        names.add(kotlin_name[:1].lower() + kotlin_name[1:])
    return names


declared_folded = {name.lower() for name in declared}


def is_declared(kotlin_name: str) -> bool:
    if swift_names(kotlin_name) & declared:
        return True
    # SCREAMING_CASE word breaks are ambiguous (BROADCAST_NODENUM is broadcastNodeNum), so compare those folded.
    return re.fullmatch(r"[A-Z][A-Z0-9_]*", kotlin_name) is not None and \
        kotlin_name.replace("_", "").lower() in declared_folded


missing = sorted((name, where) for name, where in wanted.items() if not is_declared(name))
print(f"Kotlin: {len(wanted)} non-private declarations in {len(args.kotlin)} file(s)")
if missing:
    print(f"{len(missing)} missing in Swift:")
    for name, where in missing:
        print(f"  - {name}  ({where})")
    sys.exit(1)
print("Port parity OK")
