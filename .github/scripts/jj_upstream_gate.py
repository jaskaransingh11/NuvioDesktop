"""Fail-closed intake filter. Textually clean upstream merges still need review."""
import sys

PROTECTED_TOP = (
    ".github/", "gradle/", "build.gradle", "settings.gradle",
    "gradle.properties", ".gitattributes", "composeApp/build.gradle",
    "composeApp/Configuration/", "composeApp/src/windows",
    "composeApp/src/desktopMain/kotlin/com/nuvio/app/Main.kt",
    "composeApp/src/desktopMain/native/",
    "composeApp/src/desktopMain/resources/aria2/",
)
PROTECTED_FEATURES = {
    "downloads", "updater", "player", "streams", "plugins", "p2p", "addons"
}
PROTECTED_COMPONENTS = {"network", "storage", "build"}

def is_guarded(path: str) -> bool:
    path = path.strip().replace("\\", "/")
    parts = path.split("/")
    if not path or ".." in parts or path.startswith("/"):
        return True
    if path.startswith(PROTECTED_TOP):
        return True
    if "features" in parts:
        pos = parts.index("features")
        if pos + 1 < len(parts) and parts[pos + 1] in PROTECTED_FEATURES:
            return True
    return any(part in PROTECTED_COMPONENTS for part in parts)

def inspect_paths(paths: list[str]) -> list[str]:
    return sorted({p for p in paths if is_guarded(p)})

def main() -> int:
    paths = [line.strip() for line in sys.stdin if line.strip()]
    if not paths:
        print("JJ gate: no upstream file changes to integrate")
        return 0
    blocked = inspect_paths(paths)
    print(f"JJ gate: {len(paths)} incoming files; {len(blocked)} guarded")
    for path in blocked[:40]:
        print(f"REVIEW_REQUIRED {path}")
    if blocked:
        print("Fail closed: no auto-merge, release or workstation install.")
        return 2
    print("Safe-path candidate; compile and regression tests are still required.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
