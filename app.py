"""Local OCR workbench. Files stay in memory; only translation contacts the network."""
from __future__ import annotations

import io
import logging
import re
import threading
import time
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium
import requests
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageOps, UnidentifiedImageError
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent
app = FastAPI(title="拾字 · OCR 工作台")
app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")
MAX_BYTES = 40 * 1024 * 1024
Image.MAX_IMAGE_PIXELS = 40_000_000
# PDFium and the shared inference engine must not be called concurrently.
processing_lock = threading.Lock()
engine = None


@app.get("/")
def index():
    return FileResponse(ROOT / "static" / "index.html")


@app.get("/api/health")
def health():
    return {"status": "ok", "ocr_loaded": engine is not None}


def english_present(text: str) -> bool:
    return bool(re.search(r"[A-Za-z]{2,}", text))


def selected_pages(spec: str, count: int) -> list[int]:
    if not spec.strip():
        if count > 100:
            raise ValueError("PDF 超过 100 页，请填写页码范围，每次最多识别 100 页。")
        return list(range(count))
    pages = set()
    for part in spec.replace("，", ",").split(","):
        match = re.fullmatch(r"\s*(\d+)(?:\s*-\s*(\d+))?\s*", part)
        if not match:
            raise ValueError("页码格式不正确，请使用 1-5,8,10。")
        start, end = int(match[1]), int(match[2] or match[1])
        if start < 1 or end < start or end > count:
            raise ValueError(f"页码必须在 1 到 {count} 之间，且起始页不能大于结束页。")
        if end - start >= 100:
            raise ValueError("每次最多识别 100 页。")
        pages.update(range(start - 1, end))
        if len(pages) > 100:
            raise ValueError("每次最多识别 100 页。")
    return sorted(pages)


def recognize_image(image: Image.Image) -> tuple[str, list[dict]]:
    global engine
    if engine is None:
        from rapidocr import RapidOCR
        engine = RapidOCR()
    image = ImageOps.exif_transpose(image).convert("RGB")
    # Preserve small letters while bounding inference memory on enormous photographs.
    image.thumbnail((3600, 3600), Image.Resampling.LANCZOS)
    result = engine(np.array(image))
    lines = []
    if result.txts is not None:
        for text, score in zip(result.txts, result.scores):
            lines.append({"text": str(text), "confidence": round(float(score), 4)})
    return "\n".join(line["text"] for line in lines), lines


def page_result(number: int, text: str, method: str, lines=None):
    lines = lines or []
    return {"number": number, "text": text.strip(), "method": method,
            "lines": lines, "has_english": english_present(text),
            "low_confidence": sum(line["confidence"] < .85 for line in lines)}


def extract(data: bytes, is_pdf: bool, page_range: str, mode: str):
    pages = []
    with processing_lock:
        if is_pdf:
            try:
                document = pdfium.PdfDocument(data)
            except pdfium.PdfiumError as exc:
                raise ValueError("无法打开 PDF，请确认文件完整且没有密码保护。") from exc
            try:
                total = len(document)
                for index in selected_pages(page_range, total):
                    page = document[index]
                    try:
                        textpage = page.get_textpage()
                        try:
                            text = textpage.get_text_range().replace("\r\n", "\n")
                        finally:
                            textpage.close()
                        # A sparse text layer (page numbers, scanner headers) is not sufficient.
                        useful = len(re.findall(r"[\w\u4e00-\u9fff]", text))
                        if mode != "ocr" and useful >= 20 and "\ufffd" not in text:
                            pages.append(page_result(index + 1, text, "text"))
                        else:
                            width, height = page.get_size()
                            scale = min(3.0, 3600 / max(width, height))
                            bitmap = page.render(scale=scale)
                            try:
                                image = bitmap.to_pil().copy()
                            finally:
                                bitmap.close()
                            try:
                                text, lines = recognize_image(image)
                            finally:
                                image.close()
                            pages.append(page_result(index + 1, text, "ocr", lines))
                    finally:
                        page.close()
            finally:
                document.close()
        else:
            try:
                with Image.open(io.BytesIO(data)) as image:
                    if image.width * image.height > Image.MAX_IMAGE_PIXELS:
                        raise ValueError("图片超过 4000 万像素，请缩小后重试。")
                    text, lines = recognize_image(image)
            except (UnidentifiedImageError, OSError, Image.DecompressionBombError) as exc:
                raise ValueError("无法读取图片，请使用完整的 PNG、JPG、WEBP、BMP 或 TIFF 文件。") from exc
            total = 1
            pages.append(page_result(1, text, "ocr", lines))
    return {"pages": pages, "total_pages": total,
            "has_english": any(p["has_english"] for p in pages)}


@app.post("/api/recognize")
def recognize(file: UploadFile = File(...), page_range: str = Form(""), mode: str = Form("auto")):
    if mode not in ("auto", "ocr"):
        raise HTTPException(400, "未知识别模式。")
    data = file.file.read(MAX_BYTES + 1)
    if not data:
        raise HTTPException(400, "文件是空的。")
    if len(data) > MAX_BYTES:
        raise HTTPException(413, "文件不能超过 40 MB。")
    started = time.monotonic()
    try:
        result = extract(data, data.lstrip().startswith(b"%PDF-"), page_range, mode)
        result["elapsed"] = round(time.monotonic() - started, 1)
        return result
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    except Exception as exc:
        logging.exception("OCR failed")
        raise HTTPException(500, "识别失败。首次使用请检查模型下载网络；也可以缩小文件后重试，详细原因见终端。") from exc


class TranslationInput(BaseModel):
    text: str = Field(min_length=1, max_length=60000)
    provider: str = "mymemory"


def chunks(text: str, limit: int = 1500):
    """Split at sentence/word boundaries, with a hard cap for long unbroken strings."""
    while text:
        if len(text) <= limit:
            yield text
            break
        cut = max(text.rfind("\n", 0, limit), text.rfind(". ", 0, limit), text.rfind(" ", 0, limit))
        cut = cut + 1 if cut > limit // 2 else limit
        yield text[:cut]
        text = text[cut:]


def translate_text(text: str, provider: str) -> str:
    translated = []
    with requests.Session() as session:
        # MyMemory caps q at 500 UTF-8 bytes; 120 codepoints also covers CJK.
        for chunk in chunks(text, 120 if provider == "mymemory" else 1500):
            if not english_present(chunk):
                translated.append(chunk)
                continue
            if provider == "google":
                response = session.get("https://translate.googleapis.com/translate_a/single",
                    params={"client": "gtx", "sl": "auto", "tl": "zh-CN", "dt": "t", "q": chunk}, timeout=(8, 35))
                response.raise_for_status()
                payload = response.json()
                value = "".join(item[0] for item in payload[0] if item[0])
            else:
                response = session.get("https://api.mymemory.translated.net/get",
                    params={"q": chunk, "langpair": "en|zh-CN"}, timeout=(8, 35))
                response.raise_for_status()
                payload = response.json()
                if str(payload.get("responseStatus")) != "200" or payload.get("quotaFinished"):
                    raise ValueError("翻译服务额度已用完或请求被拒绝，请切换服务后重试。")
                import html
                value = html.unescape(payload["responseData"]["translatedText"])
            if not value.strip():
                raise ValueError("翻译服务返回了空结果，请重试或切换服务。")
            translated.append(value)
    return "\n".join(translated)


@app.post("/api/translate")
def translate(body: TranslationInput):
    if body.provider not in ("google", "mymemory"):
        raise HTTPException(400, "未知翻译服务。")
    if not body.text.strip():
        raise HTTPException(400, "请先输入或识别文字。")
    try:
        return {"text": translate_text(body.text, body.provider)}
    except ValueError as exc:
        raise HTTPException(502, str(exc)) from exc
    except Exception as exc:
        logging.exception("Translation failed")
        raise HTTPException(502, "连接翻译服务失败，请检查网络，或切换翻译服务后重试。识别原文已保留。") from exc
