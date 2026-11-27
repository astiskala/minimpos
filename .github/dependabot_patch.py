"""Check whether a Gradle catalog edit is eligible for Dependabot automerge."""

import re
import sys
import tomllib
from pathlib import Path

STABLE = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", re.ASCII)
MANUAL_GROUPS = ("com.adyen", "com.squareup.okhttp3", "xerces", "com.sun.mail")


def stable_patch(old, new):
    before, after = [
        STABLE.fullmatch(value) if isinstance(value, str) else None
        for value in (old, new)
    ]
    return (
        before is not None
        and after is not None
        and before.groups()[:2] == after.groups()[:2]
        and int(after[3]) > int(before[3])
    )


def eligible(base, head):
    old = base.pop("versions", {})
    new = head.pop("versions", {})
    if base != head or old.keys() != new.keys():
        return False
    changed = {key for key in old if old[key] != new[key]}
    if not changed or not all(stable_patch(old[key], new[key]) for key in changed):
        return False

    def version_ref(entry):
        version = entry.get("version") if isinstance(entry, dict) else None
        return version.get("ref") if isinstance(version, dict) else None

    plugin_versions = {version_ref(entry) for entry in base.get("plugins", {}).values()}
    if changed & plugin_versions:
        return False
    library_versions = set()
    for entry in base.get("libraries", {}).values():
        ref = version_ref(entry)
        if ref not in changed:
            continue
        group = entry.get("group") or entry.get("module", "").partition(":")[0]
        if not group or any(group == prefix or group.startswith(prefix + ".") for prefix in MANUAL_GROUPS):
            return False
        library_versions.add(ref)
    return changed == library_versions


if __name__ == "__main__":
    base, head = [tomllib.loads(Path(path).read_text(encoding="utf-8")) for path in sys.argv[1:]]
    sys.exit(0 if eligible(base, head) else 1)
