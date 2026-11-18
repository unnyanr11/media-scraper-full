"""Keep the Windows x64 Playwright driver, its metadata, and its licenses."""
from pathlib import Path
import sys
from zipfile import ZipFile

jar = Path(sys.argv[1])
output = jar.with_suffix(".tmp")
with ZipFile(jar) as source, ZipFile(output, "w") as target:
    if not any(name.startswith("driver/win32_x64/") for name in source.namelist()):
        raise SystemExit("The Windows x64 Playwright driver was not found")
    for member in source.infolist():
        if member.filename.startswith("driver/") and member.filename != "driver/":
            if not member.filename.startswith("driver/win32_x64/"):
                continue
        target.writestr(member, source.read(member))
output.replace(jar)
print(f"Windows Playwright driver: {jar.stat().st_size / 1024**2:.1f} MiB")
