"""Exercise the EXE alone, with Python removed from PATH and Internet blocked for it."""
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import time

import requests
from PIL import Image, ImageDraw, ImageFont
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parents[1]
ISOLATED = ROOT / "tmp" / "电脑独立运行验证"
ISOLATED.mkdir(exist_ok=True)
EXE = ISOLATED / "拾字.exe"
shutil.copy2(ROOT / "dist/windows/Shizi.exe", EXE)
assert [p.name for p in ISOLATED.iterdir()] == ["拾字.exe"], "Validation folder must initially contain only the EXE"
token = secrets.token_urlsafe(24)
port = 18879
url = f"http://127.0.0.1:{port}"
env = dict(os.environ)
env.pop("PYTHONHOME", None)
env.pop("PYTHONPATH", None)
env["PATH"] = str(Path(env["SYSTEMROOT"]) / "System32")
env["HTTPS_PROXY"] = env["HTTP_PROXY"] = "http://127.0.0.1:9"
env["NO_PROXY"] = "127.0.0.1,localhost"
# Deliberately launch from a different directory. Unpacking must still be next
# to the EXE, rather than in the caller's directory or the C: temp folder.
process = subprocess.Popen([str(EXE), "--headless", "--port", str(port), "--control-token", token],
    cwd=ROOT / "tmp", env=env)
report = {}
try:
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError(f"EXE exited before startup: {process.returncode}")
        try:
            response = requests.get(url + "/api/desktop", timeout=1)
            response.raise_for_status()
            report["runtime"] = response.json()
            break
        except requests.RequestException:
            time.sleep(.5)
    else:
        raise RuntimeError("EXE startup timed out")
    assert report["runtime"]["frozen"]
    assert Path(report["runtime"]["runtime_dir"]).is_relative_to(ISOLATED)
    assert requests.get(url, timeout=5).status_code == 200
    assert requests.get(url + "/static/app.js", timeout=5).status_code == 200
    fixture = Image.new("RGB", (1400, 460), "white")
    draw = ImageDraw.Draw(fixture)
    font = ImageFont.truetype(str(Path(env["SYSTEMROOT"]) / "Fonts/msyh.ttc"), 44)
    for y, line in ((60, "Image to Text"), (160, "图片文字识别测试"),
                    (260, "The meeting starts at nine tomorrow.")):
        draw.text((70, y), line, font=font, fill="black")
    fixture_data = io.BytesIO()
    fixture.save(fixture_data, "PNG")
    fixture.close()
    sample = fixture_data.getvalue()
    response = requests.post(url + "/api/recognize", files={"file": ("mixed.png", sample, "image/png")}, timeout=90)
    response.raise_for_status()
    text = response.json()["pages"][0]["text"]
    assert "Image to Text" in text and "图片文字识别测试" in text, text
    report["offline_mixed_image"] = text
    pdf = io.BytesIO()
    with Image.open(io.BytesIO(sample)) as image:
        image.convert("RGB").save(pdf, "PDF", resolution=120)
    response = requests.post(url + "/api/recognize", files={"file": ("scan.pdf", pdf.getvalue(), "application/pdf")}, timeout=90)
    response.raise_for_status()
    assert "图片文字识别测试" in response.json()["pages"][0]["text"]
    report["offline_scanned_pdf"] = "passed"
    native = io.BytesIO(); document = canvas.Canvas(native)
    document.drawString(50, 750, "This document contains searchable English text.")
    document.save()
    response = requests.post(url + "/api/recognize", files={"file": ("text.pdf", native.getvalue(), "application/pdf")}, timeout=30)
    response.raise_for_status()
    assert response.json()["pages"][0]["method"] == "text"
    report["native_pdf"] = "passed"
    response = requests.post(url + "/api/translate", json={"text": "Hello world", "provider": "mymemory"}, timeout=40)
    assert response.status_code == 502
    assert "原文已保留" in response.json()["detail"]
    report["offline_translation_error"] = "passed"
    assert requests.post(url + "/api/desktop/quit", timeout=5).status_code == 403
finally:
    try:
        requests.post(url + "/api/desktop/quit", headers={"X-Shizi-Token": token}, timeout=5).raise_for_status()
    except requests.RequestException:
        pass
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        raise RuntimeError("EXE did not close cleanly")
assert process.returncode == 0, process.returncode
assert [p.name for p in ISOLATED.iterdir()] == ["拾字.exe"], "Runtime files were not cleaned up"
report["automatic_cleanup"] = "passed"
# Also start the actual Tk launcher with browser auto-open disabled. Its server
# only starts after the window is constructed, proving bundled Tcl/Tk works.
gui_token = secrets.token_urlsafe(24)
gui = subprocess.Popen([str(EXE), "--no-browser", "--port", str(port), "--control-token", gui_token],
    cwd=ROOT / "tmp", env=env)
for _ in range(120):
    if gui.poll() is not None: raise RuntimeError("GUI launcher failed to start")
    try:
        response = requests.get(url + "/api/desktop", timeout=1)
        response.raise_for_status()
        break
    except requests.RequestException:
        time.sleep(.5)
else: raise RuntimeError("GUI launcher startup timed out")
requests.post(url + "/api/desktop/quit", headers={"X-Shizi-Token": gui_token}, timeout=5).raise_for_status()
gui.wait(timeout=20)
assert gui.returncode == 0
assert [p.name for p in ISOLATED.iterdir()] == ["拾字.exe"]
report["gui_launcher_and_exit"] = "passed"
(ROOT / "dist/windows/verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=True, indent=2))
