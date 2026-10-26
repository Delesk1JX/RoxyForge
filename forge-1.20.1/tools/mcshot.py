"""Screenshot the Minecraft window.

`um win shot` uses ffmpeg's gfxcapture, which fails on the Minecraft window with "Generic error in an
external library". GDI capture of the whole desktop works, so this takes the window rectangle from WinDrive
and crops the frame out of a desktop grab.

    python mcshot.py out.png [scale]
"""
import re
import subprocess
import sys
from pathlib import Path

UM = Path(r"C:\Users\Alex\universal-modder\bin\um.cmd")
FFMPEG = Path(r"C:\Users\Alex\AppData\Local\Programs\ffmpeg\bin\ffmpeg.exe")
PROC = "javaw"


def rect() -> tuple[int, int, int, int]:
    finished = subprocess.run(
        [str(UM), "win", "drive", "--proc", PROC, "rect"],
        capture_output=True, text=True, shell=False,
    )
    match = re.search(r"rect -> (-?\d+) (-?\d+) (\d+) (\d+)", finished.stdout)
    if not match:
        sys.exit(f"no window for {PROC}: {finished.stdout}{finished.stderr}")
    return tuple(int(value) for value in match.groups())


def main() -> None:
    target = Path(sys.argv[1])
    scale = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
    x, y, width, height = rect()

    crop = f"crop={width}:{height}:{x}:{y}"
    target.parent.mkdir(parents=True, exist_ok=True)
    for output, filters in ((target, crop), (target.with_stem(f"{target.stem}_small"),
                                            f"{crop},scale=iw*{scale}:-1")):
        subprocess.run(
            [str(FFMPEG), "-y", "-loglevel", "error", "-f", "gdigrab", "-framerate", "1",
             "-i", "desktop", "-frames:v", "1", "-vf", filters, str(output)],
            check=True,
        )
    print(f"wrote {target} ({width}x{height} at {x},{y}) and {target.with_stem(f'{target.stem}_small')}")


if __name__ == "__main__":
    main()
