# Single distributable EXE, with the runtime and OCR models included.
from pathlib import Path
from PyInstaller.utils.hooks import collect_data_files, collect_submodules, collect_dynamic_libs

project = Path(SPECPATH)
datas = [(str(project / 'static'), 'static')]
datas += collect_data_files('rapidocr')
hiddenimports = collect_submodules('rapidocr') + [
    'uvicorn.logging', 'uvicorn.protocols.http.h11_impl', 'uvicorn.lifespan.on',
]
binaries = collect_dynamic_libs('onnxruntime')
a = Analysis([str(project / 'desktop.py')], pathex=[str(project)],
    binaries=binaries, datas=datas, hiddenimports=hiddenimports,
    excludes=['pytest', 'reportlab', 'httpx', 'matplotlib', 'scipy', 'torch',
              'tensorflow', 'paddle', 'openvino', 'tensorrt', 'IPython'],
    noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name='Shizi', debug=False,
    bootloader_ignore_signals=False, strip=False, upx=False, console=False,
    # Extract in the launch working directory instead of the C: system temp.
    # The one-file bootloader removes its _MEI* directory on a normal exit.
    runtime_tmpdir='.', disable_windowed_traceback=False)
