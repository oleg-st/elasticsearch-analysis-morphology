#!/usr/bin/env python3
"""Export pickle surnames for checkSurnameCoverage; the dictionary is never shipped.

Run this script, then: gradlew checkSurnameCoverage
"""
import argparse
import hashlib
import io
import json
import pickle
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class StringSetUnpickler(pickle.Unpickler):
    def find_class(self, module, name):
        raise pickle.UnpicklingError("Pickle classes/globals are not supported")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "data/surnames_filtered.pickle")
    parser.add_argument("--output", type=Path, default=ROOT / "build/surname-coverage/nominatives.txt")
    args = parser.parse_args()
    raw = args.input.read_bytes()
    values = StringSetUnpickler(io.BytesIO(raw)).load()
    if not isinstance(values, (set, frozenset)) or not all(isinstance(v, str) for v in values):
        raise ValueError("Expected a set of surname strings")
    normalized = {v.lower().replace("ё", "е") for v in values}
    valid = sorted(v for v in normalized if re.fullmatch(r"[а-я]{2,}(?:-[а-я]{2,}){0,3}", v))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(valid) + "\n", encoding="utf-8")
    metadata = {
        "source": args.input.name, "source_sha256": hashlib.sha256(raw).hexdigest(),
        "source_entries": len(values), "normalized_entries": len(normalized),
        "accepted_entries": len(valid), "rejected_entries": sorted(normalized.difference(valid)),
    }
    args.output.with_name("source.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(valid)} surnames; rejected {len(metadata['rejected_entries'])}. Run gradlew checkSurnameCoverage.")


if __name__ == "__main__":
    main()
