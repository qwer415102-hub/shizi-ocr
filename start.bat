@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  python -m venv .venv
  if errorlevel 1 goto failed
)
".venv\Scripts\python.exe" -c "import fastapi,uvicorn,rapidocr,onnxruntime,pypdfium2,PIL,requests,multipart" >nul 2>nul
if errorlevel 1 (
  ".venv\Scripts\python.exe" -m pip install -r requirements.txt
  if errorlevel 1 goto failed
)
echo 正在启动拾字，请打开 http://127.0.0.1:8765
echo 关闭此窗口即可停止服务。
".venv\Scripts\python.exe" run.py
pause
exit /b
:failed
echo 安装失败。请确认已安装 64 位 Python 3.14，并检查网络。
pause
