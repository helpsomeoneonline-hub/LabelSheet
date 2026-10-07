from pathlib import Path
p = Path("lsbuild/app/src/main/java/com/byso/labelsheet/MainActivity.java")
s = p.read_text()
broken = '''        statusText.setText(canvasView.selectedSizeText() + "   •   " + orientation + " Letter   •   Autosaved
" + printerState);'''
fixed = '''        statusText.setText(canvasView.selectedSizeText() + "   •   " + orientation + " Letter   •   Autosaved\\n" + printerState);'''
if broken not in s:
    raise SystemExit("status line fix target not found")
p.write_text(s.replace(broken, fixed))
print("v1.4 status line fixed")
