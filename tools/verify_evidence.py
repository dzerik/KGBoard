"""Validate explicit verification records without inferring or changing their status."""

import argparse
import json
from pathlib import Path


def validate_row(row, root=None):
    if row.get("status") not in {"NotRun", "Passed", "Failed", "Blocked"}:
        return ["Unknown status"]
    if row["status"] != "Passed":
        return []
    if not row.get("build") or not row.get("evidence"):
        return ["Passed requires build and evidence"]
    errors = []
    for field in ("product", "jbr", "os", "plugin", "feature", "command"):
        if not isinstance(row.get(field), str) or not row[field].strip():
            errors.append(f"Passed requires {field}")
    if row.get("kind") == "hardware":
        for field in ("model", "openrgb", "endpoint", "device", "observation"):
            if not isinstance(row.get(field), str) or not row[field].strip():
                errors.append(f"Passed hardware requires {field}")
    root = Path(root or Path.cwd()).resolve()
    if not isinstance(row["evidence"], list):
        return errors + ["Evidence must be a list of repository-relative paths"]
    for entry in row["evidence"]:
        if not isinstance(entry, str) or not entry.strip():
            errors.append("Evidence path must be a nonempty string")
            continue
        path = Path(entry)
        if path.is_absolute() or not (root / path).resolve().is_relative_to(root):
            errors.append(f"Evidence must be repository-relative: {entry}")
        elif not (root / path).is_file():
            errors.append(f"Missing evidence: {entry}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    rows = json.loads(args.report.read_text(encoding="utf-8"))
    if not isinstance(rows, list):
        parser.error("Report must be a JSON array")
    invalid = False
    for index, row in enumerate(rows, 1):
        errors = validate_row(row, args.root) if isinstance(row, dict) else ["Row must be an object"]
        for error in errors:
            print(f"Row {index}: {error}")
        invalid |= bool(errors)
    if not invalid:
        print(f"Validated {len(rows)} explicit verification records")
    return int(invalid)


if __name__ == "__main__":
    raise SystemExit(main())
