"""Portable Windows launcher; runs the bundled app and offers a visible exit button."""
from __future__ import annotations

import argparse
import ctypes
import io
import logging
import secrets
import socket
import sys
import threading
import time
import urllib.request
import webbrowser
from pathlib import Path

# Windowed PyInstaller applications have no console streams. Third-party loggers
# still expect file-like stdout/stderr, so provide memory-only streams.
if sys.stdout is None:
    sys.stdout = io.StringIO()
if sys.stderr is None:
    sys.stderr = io.StringIO()

import uvicorn
from fastapi import Header, HTTPException
from app import app

VERSION = "1.0.0"


def choose_port(preferred: int) -> int:
    with socket.socket() as candidate:
        try:
            candidate.bind(("127.0.0.1", preferred))
        except OSError:
            candidate.bind(("127.0.0.1", 0))
        return candidate.getsockname()[1]


class DesktopService:
    def __init__(self, port=18765, token=None):
        self.port = choose_port(port)
        self.token = token or secrets.token_urlsafe(32)
        self.url = f"http://127.0.0.1:{self.port}"
        self.server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=self.port,
            log_config=None, access_log=False, timeout_graceful_shutdown=3))
        app.add_api_route("/api/desktop", self.info, methods=["GET"])
        app.add_api_route("/api/desktop/quit", self.quit, methods=["POST"])

    def info(self):
        return {"version": VERSION, "frozen": bool(getattr(sys, "frozen", False)),
                "runtime_dir": str(Path(__file__).resolve().parent), "port": self.port}

    def quit(self, x_shizi_token: str = Header(default="")):
        if not secrets.compare_digest(x_shizi_token, self.token):
            raise HTTPException(403, "无效的关闭请求。")
        self.server.should_exit = True
        return {"status": "closing"}

    def run(self):
        self.server.run()


def show_window(service: DesktopService, auto_open=True):
    import tkinter as tk
    from tkinter import messagebox

    window = tk.Tk()
    window.title("拾字 · 图片与 PDF 文字工作台")
    window.geometry("470x345")
    window.resizable(False, False)
    window.configure(background="#f7f8fb")
    frame = tk.Frame(window, background="#f7f8fb", padx=30, pady=24)
    frame.pack(fill="both", expand=True)
    tk.Label(frame, text="拾字  /  TEXT STUDIO", bg="#f7f8fb", fg="#292e43",
             font=("Microsoft YaHei UI", 19, "bold")).pack(anchor="w")
    tk.Label(frame, text="图片与 PDF · 本地中英文识别 · 英译中", bg="#f7f8fb", fg="#858d9f",
             font=("Microsoft YaHei UI", 10)).pack(anchor="w", pady=(9, 22))
    state = tk.StringVar(value="正在启动，请稍候…")
    tk.Label(frame, textvariable=state, bg="#f7f8fb", fg="#7362db",
             font=("Microsoft YaHei UI", 11)).pack(anchor="w")
    tk.Label(frame, text="识别模型已内置，断网也能识别。\n界面在默认浏览器中打开，翻译需要联网。",
             justify="left", bg="#f7f8fb", fg="#858d9f", font=("Microsoft YaHei UI", 10)).pack(anchor="w", pady=13)
    opened = False
    thread = threading.Thread(target=service.run, daemon=True)
    thread.start()

    def open_ui():
        webbrowser.open(service.url)

    def close():
        service.server.should_exit = True
        window.destroy()

    buttons = tk.Frame(frame, bg="#f7f8fb")
    buttons.pack(fill="x", pady=(8, 0))
    open_button = tk.Button(buttons, text="打开使用界面", command=open_ui, state="disabled",
        bg="#7362db", fg="white", activebackground="#6553c8", activeforeground="white",
        font=("Microsoft YaHei UI", 11), relief="flat", padx=19, pady=9)
    open_button.pack(side="left")
    tk.Button(buttons, text="退出软件", command=close, font=("Microsoft YaHei UI", 11),
        relief="flat", bg="#ece9f8", fg="#7362db", padx=19, pady=9).pack(side="right")
    window.protocol("WM_DELETE_WINDOW", close)

    def poll():
        nonlocal opened
        if service.server.started and not thread.is_alive():
            window.destroy()
            return
        if service.server.started:
            state.set("已启动 · 关闭此窗口即可退出软件")
            open_button.configure(state="normal")
            if not opened:
                opened = True
                if auto_open:
                    open_ui()
        elif not thread.is_alive():
            messagebox.showerror("启动失败", "本地服务未能启动，请关闭后重新打开软件。")
            window.destroy()
            return
        window.after(250, poll)

    window.after(250, poll)
    window.mainloop()
    service.server.should_exit = True
    thread.join(timeout=5)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--headless", action="store_true", help="Run without opening the window (verification only)")
    parser.add_argument("--no-browser", action="store_true", help="Wait for the Open Interface button instead of opening a browser automatically")
    parser.add_argument("--port", type=int, default=18765)
    parser.add_argument("--control-token", default=None)
    args = parser.parse_args()
    try:
        service = DesktopService(args.port, args.control_token)
        if args.headless:
            service.run()
        else:
            show_window(service, auto_open=not args.no_browser)
    except Exception:
        logging.exception("Desktop startup failed")
        if not args.headless:
            ctypes.windll.user32.MessageBoxW(0, "软件启动失败，请确认 EXE 所在目录可写，并重新打开。", "拾字", 0x10)
        raise


if __name__ == "__main__":
    main()
