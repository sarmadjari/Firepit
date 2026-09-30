#!/usr/bin/env python3
"""Checks that the iOS data layer runs the same SQL and exposes the same DAO members as Android.

Compares android/core/database/.../Daos.kt with
ios/Packages/FirepitKit/Sources/FirepitData/Daos.swift:
  * every Android DAO interface has a Swift type of the same name;
  * every Kotlin DAO function (abstract or with a body) has a Swift function of the same name in that type;
  * every SQL statement in a Kotlin @Query appears in the Swift file, after normalising whitespace and turning
    Room's named parameters (:name) and Swift's positional ones (?) into the same placeholder.
Exit status is non-zero on any mismatch.

Usage: scripts/check-dao-parity.py [path/to/repo]   (default: this repository)
"""
import os
import re
import sys

android_repo = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
kotlin_path = os.path.join(android_repo, "android/core/database/src/main/java/com/getfirepit/core/database/Daos.kt")
swift_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                          "ios/Packages/FirepitKit/Sources/FirepitData/Daos.swift")

kotlin = open(kotlin_path, encoding="utf-8").read()
swift = open(swift_path, encoding="utf-8").read()


def normalise(sql: str) -> str:
    # Swift builds IN-lists at run time: IN (\(placeholders)) is Room's IN (:ids).
    sql = re.sub(r"\\\([^)]*\)", "?", sql)
    sql = re.sub(r":[A-Za-z_][A-Za-z0-9_]*", "?", sql)
    sql = re.sub(r"\s+", " ", sql).strip()
    sql = re.sub(r"\(\s+", "(", sql)
    sql = re.sub(r"\s+\)", ")", sql)
    return sql.rstrip(";")


def kotlin_string(expr: str) -> str:
    """Concatenates the string literals in a Kotlin annotation argument ("a" + "b", or a raw triple-quoted string)."""
    parts = re.findall(r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"', expr, re.S)
    return "".join(a if a else b for a, b in parts)


def swift_string(literal: str) -> str:
    if literal.startswith('"""'):
        body = literal[3:-3]
        return body
    return literal[1:-1].replace('\\"', '"')


def block_bodies(source: str, keyword: str):
    """Yields (name, body) for each `keyword Name ... { body }` at any depth, matching braces."""
    for match in re.finditer(rf"\b{keyword}\s+([A-Z][A-Za-z0-9_]*)[^{{]*\{{", source):
        depth, i = 1, match.end()
        while depth and i < len(source):
            if source[i] == "{":
                depth += 1
            elif source[i] == "}":
                depth -= 1
            i += 1
        yield match.group(1), source[match.end():i - 1]


problems = []

# Kotlin: interfaces, their functions, and extension functions on them (fun XDao.name).
kotlin_members: dict[str, set[str]] = {}
for name, body in block_bodies(kotlin, "interface"):
    kotlin_members[name] = set(re.findall(r"\bfun\s+([a-z][A-Za-z0-9_]*)\s*\(", body))
for dao, fun in re.findall(r"\bfun\s+([A-Z][A-Za-z0-9_]*Dao)\.([a-z][A-Za-z0-9_]*)\s*\(", kotlin):
    kotlin_members.setdefault(dao, set()).add(fun)

swift_members: dict[str, set[str]] = {}
for keyword in ("struct", "extension"):
    for name, body in block_bodies(swift, keyword):
        swift_members.setdefault(name, set()).update(re.findall(r"\bfunc\s+([a-z][A-Za-z0-9_]*)\s*[(<]", body))

for dao, funs in sorted(kotlin_members.items()):
    if dao not in swift_members:
        problems.append(f"missing Swift type {dao}")
        continue
    for fun in sorted(funs - swift_members[dao]):
        problems.append(f"{dao}.{fun} has no Swift counterpart")

# SQL: every Kotlin @Query must appear in Swift.
kotlin_sql = []
for match in re.finditer(r"@Query\(", kotlin):
    depth, i = 1, match.end()
    while depth and i < len(kotlin):
        if kotlin.startswith('"""', i):
            end = kotlin.index('"""', i + 3)
            i = end + 3
            continue
        if kotlin[i] == '"':
            i += 1
            while kotlin[i] != '"':
                i += 2 if kotlin[i] == "\\" else 1
            i += 1
            continue
        if kotlin[i] == "(":
            depth += 1
        elif kotlin[i] == ")":
            depth -= 1
        i += 1
    kotlin_sql.append(normalise(kotlin_string(kotlin[match.end():i - 1])))

swift_sql = {normalise(swift_string(lit)) for lit in re.findall(r'"""[\s\S]*?"""|"(?:[^"\\\n]|\\.)*"', swift)}
for sql in kotlin_sql:
    if sql not in swift_sql:
        problems.append(f"SQL not found in Swift: {sql}")

total_funs = sum(len(f) for f in kotlin_members.values())
print(f"Kotlin: {len(kotlin_members)} DAOs, {total_funs} functions, {len(kotlin_sql)} @Query statements")
if problems:
    print(f"{len(problems)} problem(s):")
    for problem in problems:
        print("  - " + problem)
    sys.exit(1)
print("DAO parity OK")
