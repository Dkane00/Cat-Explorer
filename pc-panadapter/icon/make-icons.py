#!/usr/bin/env python3
"""
Builds the Panadapter's desktop icon from the Android app's own launcher icon, so the two always match.

Reads app/src/main/res/drawable/ic_launcher_background.xml and ic_launcher_foreground.xml (Android vector
drawables), writes cat-explorer-panadapter.svg next to this script, then renders PNGs at the usual desktop
sizes (png/<size>/cat-explorer-panadapter.png) using GTK's image loader.

Run it again whenever the app icon changes:   python3 pc-panadapter/icon/make-icons.py
"""
import os
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable")
NAME = "cat-explorer-panadapter"
SIZES = [16, 22, 24, 32, 48, 64, 128, 256]
A = "{http://schemas.android.com/apk/res/android}"


def convert(node, out, indent="  "):
    """Writes the SVG for one Android vector node (path or group) and its children."""
    if node.tag == "path":
        attrs = [f'd="{node.get(A + "pathData")}"']
        fill, stroke = node.get(A + "fillColor"), node.get(A + "strokeColor")
        attrs.append(f'fill="{fill}"' if fill else 'fill="none"')
        if stroke:
            attrs.append(f'stroke="{stroke}" stroke-width="{node.get(A + "strokeWidth", "1")}"')
        out.append(f"{indent}<path {' '.join(attrs)}/>")
    elif node.tag == "group":
        px, py = float(node.get(A + "pivotX", 0)), float(node.get(A + "pivotY", 0))
        sx, sy = node.get(A + "scaleX", "1"), node.get(A + "scaleY", "1")
        out.append(f'{indent}<g transform="translate({px} {py}) scale({sx} {sy}) translate({-px} {-py})">')
        for child in node:
            convert(child, out, indent + "  ")
        out.append(f"{indent}</g>")


def main():
    # framed like the app's own launcher PNGs: the dial nearly fills a rounded square
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="16.5 16.5 75 75" width="256" height="256">',
           '  <clipPath id="shape"><rect x="16.5" y="16.5" width="75" height="75" rx="16"/></clipPath>',
           '  <g clip-path="url(#shape)">']
    for part in ("background", "foreground"):
        root = ET.parse(os.path.join(RES, f"ic_launcher_{part}.xml")).getroot()
        for child in root:
            convert(child, out, "    ")
    out += ["  </g>", "</svg>", ""]
    svg = os.path.join(HERE, f"{NAME}.svg")
    with open(svg, "w") as f:
        f.write("\n".join(out))
    print("wrote", svg)

    import gi
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import GdkPixbuf
    for size in SIZES:
        d = os.path.join(HERE, "png", str(size))
        os.makedirs(d, exist_ok=True)
        GdkPixbuf.Pixbuf.new_from_file_at_size(svg, size, size).savev(os.path.join(d, f"{NAME}.png"), "png", [], [])
    print("rendered", ", ".join(map(str, SIZES)))


if __name__ == "__main__":
    main()
