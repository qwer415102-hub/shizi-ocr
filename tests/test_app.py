import io
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image, ImageDraw, ImageFont
from reportlab.pdfgen import canvas

import app

client = TestClient(app.app)


@pytest.fixture(scope="module")
def image_bytes():
    image = Image.new("RGB", (1400, 460), "white")
    draw = ImageDraw.Draw(image)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    if not font_path.exists():
        pytest.skip("This mixed-language OCR fixture requires Microsoft YaHei.")
    font = ImageFont.truetype(str(font_path), 44)
    draw.text((70, 60), "Image to Text", font=font, fill="black")
    draw.text((70, 160), "图片文字识别测试", font=font, fill="black")
    draw.text((70, 260), "The meeting starts at nine tomorrow.", font=font, fill="black")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def test_real_mixed_language_ocr(image_bytes):
    response = client.post("/api/recognize", files={"file": ("mixed.png", image_bytes, "image/png")})
    assert response.status_code == 200, response.text
    result = response.json()
    text = result["pages"][0]["text"]
    assert "Image to Text" in text
    assert "图片文字识别测试" in text
    assert result["has_english"] is True


def test_native_pdf_page_selection():
    buffer = io.BytesIO()
    pdf = canvas.Canvas(buffer)
    for i in range(3):
        pdf.drawString(60, 750, f"Page {i+1}: This document contains searchable English text.")
        pdf.showPage()
    pdf.save()
    response = client.post("/api/recognize", files={"file": ("native.pdf", buffer.getvalue(), "application/pdf")}, data={"page_range": "2-3"})
    assert response.status_code == 200, response.text
    result = response.json()
    assert [p["number"] for p in result["pages"]] == [2, 3]
    assert all(p["method"] == "text" for p in result["pages"])
    assert "Page 2" in result["pages"][0]["text"]


def test_scanned_pdf(image_bytes):
    buffer = io.BytesIO()
    with Image.open(io.BytesIO(image_bytes)) as image:
        image.convert("RGB").save(buffer, "PDF", resolution=120)
    response = client.post("/api/recognize", files={"file": ("scan.pdf", buffer.getvalue(), "application/pdf")})
    assert response.status_code == 200, response.text
    page = response.json()["pages"][0]
    assert page["method"] == "ocr"
    assert "Image to Text" in page["text"]
    assert "图片文字识别测试" in page["text"]


@pytest.mark.parametrize("spec", ["0", "4", "2-1", "1-3,4", "x", "1,,2"])
def test_invalid_ranges(spec):
    with pytest.raises(ValueError):
        app.selected_pages(spec, 3)


def test_ranges_deduplicate_and_reject_oversized():
    assert app.selected_pages("3,1-2,2", 3) == [0, 1, 2]
    with pytest.raises(ValueError):
        app.selected_pages("", 101)
    with pytest.raises(ValueError):
        app.selected_pages("1-10000000", 10000000)


def test_invalid_uploads():
    assert client.post("/api/recognize", files={"file": ("empty.png", b"")}).status_code == 400
    assert client.post("/api/recognize", files={"file": ("fake.png", b"not an image")}).status_code == 400
    assert client.post("/api/recognize", files={"file": ("fake.pdf", b"%PDF-invalid")}).status_code == 400


def test_translation_errors_keep_clear_message(monkeypatch):
    def fail(*args, **kwargs):
        raise app.requests.Timeout()
    monkeypatch.setattr(app.requests.Session, "get", fail)
    response = client.post("/api/translate", json={"text": "English document"})
    assert response.status_code == 502
    assert "识别原文已保留" in response.json()["detail"]
    assert client.post("/api/translate", json={"text": "Hello", "provider": "invalid"}).status_code == 400


def test_chunking_preserves_text_and_byte_budget():
    text = ("English 中文测试。 " * 100) + "x" * 300
    parts = list(app.chunks(text, 120))
    assert "".join(parts) == text
    assert all(len(part.encode("utf-8")) <= 500 for part in parts)
