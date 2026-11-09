"""Start the local app and open its UI once the server is ready."""
import threading
import time
import urllib.request
import webbrowser

import uvicorn


def open_when_ready():
    for _ in range(100):
        try:
            with urllib.request.urlopen("http://127.0.0.1:8765/api/health", timeout=1) as response:
                if response.status == 200:
                    webbrowser.open("http://127.0.0.1:8765")
                    return
        except OSError:
            time.sleep(.2)


if __name__ == "__main__":
    threading.Thread(target=open_when_ready, daemon=True).start()
    uvicorn.run("app:app", host="127.0.0.1", port=8765)
