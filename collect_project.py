import os
import sys

OUTPUT_FILE = "PROJECT_CODE.txt"
ROOT = "."

# فایل‌هایی که باید نادیده گرفته شوند (پوشه‌ها)
EXCLUDE_DIRS = {
    ".git", ".gradle", ".idea", "build", "app/build",
    "gradle", "captures", ".cxx", "node_modules",
    "__pycache__", ".dart_tool", ".kotlin"
}

# پسوندهایی که باید جمع‌آوری شوند
INCLUDE_EXTS = {
    ".kt", ".kts", ".java", ".xml", ".gradle", ".properties",
    ".json", ".md", ".txt", ".yml", ".yaml", ".toml",
    ".pro", ".cfg", ".sh", ".py", ".html", ".js", ".css",
    ".cmake", ".mk", ".gitignore"
}

# فایل‌های باینری یا بزرگ که نباید شامل شوند
EXCLUDE_FILES = {
    "gradlew", "gradlew.bat", "collect_project.py",
    "PROJECT_CODE.txt", "local.properties"
}

# حداکثر اندازه فایل (بیشتر از این = احتمالاً باینری یا تولیدشده)
MAX_SIZE = 200_000  # 200 KB

def should_include_file(path):
    filename = os.path.basename(path)
    if filename in EXCLUDE_FILES:
        return False
    ext = os.path.splitext(filename)[1].lower()
    if ext not in INCLUDE_EXTS and filename != ".gitignore":
        return False
    try:
        if os.path.getsize(path) > MAX_SIZE:
            return False
    except OSError:
        return False
    return True

def main():
    files = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        # حذف پوشه‌های ممنوعه
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for f in filenames:
            full = os.path.join(dirpath, f)
            if should_include_file(full):
                files.append(full)

    files.sort()

    total_lines = 0
    total_chars = 0

    with open(OUTPUT_FILE, "w", encoding="utf-8") as out:
        out.write("=" * 80 + "\n")
        out.write("PROJECT: MyLinuxTerminal\n")
        out.write("FULL SOURCE CODE ARCHIVE\n")
        out.write("Generated for AI analysis\n")
        out.write(f"Total files: {len(files)}\n")
        out.write("=" * 80 + "\n\n")

        # فهرست فایل‌ها
        out.write("=== FILE INDEX ===\n")
        for i, f in enumerate(files, 1):
            rel = os.path.relpath(f)
            out.write(f"{i:3d}. {rel}\n")
        out.write("\n")

        # محتوای فایل‌ها
        for i, f in enumerate(files, 1):
            rel = os.path.relpath(f)
            out.write("\n" + "=" * 80 + "\n")
            out.write(f"FILE [{i}/{len(files)}]: {rel}\n")
            out.write("=" * 80 + "\n")

            try:
                with open(f, "r", encoding="utf-8", errors="replace") as fh:
                    content = fh.read()
                out.write(content)
                if not content.endswith("\n"):
                    out.write("\n")
                total_lines += content.count("\n") + 1
                total_chars += len(content)
            except Exception as e:
                out.write(f"!!! Error reading file: {e}\n")

    size_kb = os.path.getsize(OUTPUT_FILE) / 1024
    print("=" * 60)
    print(f"✅ فایل ساخته شد: {OUTPUT_FILE}")
    print(f"📦 تعداد فایل‌ها: {len(files)}")
    print(f"📏 حجم فایل: {size_kb:.1f} KB")
    print(f"📝 تعداد خطوط کد: {total_lines:,}")
    print("=" * 60)
    print("\n📋 فایل‌های شامل‌شده:")
    for i, f in enumerate(files, 1):
        print(f"  {i:2d}. {os.path.relpath(f)}")

if __name__ == "__main__":
    main()
