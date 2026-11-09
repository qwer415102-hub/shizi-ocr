"""Install a workspace-local Android build toolchain (not needed by app users)."""
import concurrent.futures
import hashlib
import os
from pathlib import Path
import subprocess
import shutil
import sys
import zipfile
import xml.etree.ElementTree as ET

import requests

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "tmp" / "android-toolchain"
DEST.mkdir(parents=True, exist_ok=True)


def fetch(url, name, checksum=None, algorithm="sha256"):
    target = DEST / name
    if not target.exists():
        print(f"Downloading {name}", flush=True)
        with requests.get(url, stream=True, timeout=(30, 180)) as response:
            response.raise_for_status()
            with target.with_suffix(".part").open("wb") as output:
                for chunk in response.iter_content(1024 * 1024):
                    output.write(chunk)
        target.with_suffix(".part").replace(target)
    if checksum:
        actual = hashlib.file_digest(target.open("rb"), algorithm).hexdigest()
        if actual.lower() != checksum.lower():
            raise RuntimeError(f"Checksum mismatch: {name}")
    return target


def setup_java():
    if list(DEST.glob("jdk-*/bin/java.exe")):
        return
    response = requests.get("https://api.adoptium.net/v3/assets/latest/17/hotspot",
        params={"architecture": "x64", "image_type": "jdk", "os": "windows", "vendor": "eclipse"}, timeout=40)
    response.raise_for_status()
    package = response.json()[0]["binary"]["package"]
    archive = fetch(package["link"], "jdk17.zip", package["checksum"])
    with zipfile.ZipFile(archive) as source:
        source.extractall(DEST)
    print("JDK ready", flush=True)


def setup_gradle():
    if (DEST / "gradle-8.11.1/bin/gradle.bat").exists():
        return
    url = "https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip"
    digest = requests.get(url + ".sha256", timeout=30)
    digest.raise_for_status()
    archive = fetch(url, "gradle.zip", digest.text.strip())
    with zipfile.ZipFile(archive) as source:
        source.extractall(DEST)
    print("Gradle ready", flush=True)


def setup_sdk():
    if (DEST / "sdk/cmdline-tools/16.0/bin/sdkmanager.bat").exists():
        return
    response = requests.get("https://dl.google.com/android/repository/repository2-1.xml", timeout=60)
    response.raise_for_status()
    root = ET.fromstring(response.content)
    package = next(p for p in root.findall("remotePackage") if p.attrib["path"] == "cmdline-tools;16.0")
    archive = next(a for a in package.findall("archives/archive") if a.findtext("host-os") == "windows")
    complete = archive.find("complete")
    zip_path = fetch("https://dl.google.com/android/repository/" + complete.findtext("url"),
        "android-commandline16.zip", complete.findtext("checksum"), "sha1")
    destination = DEST / "sdk/cmdline-tools"
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as source:
        for entry in source.infolist():
            relative = Path(entry.filename).relative_to("cmdline-tools")
            target = destination / "16.0" / relative
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.open(entry) as input_file, target.open("wb") as output_file:
                    shutil.copyfileobj(input_file, output_file)
    print("Android command-line tools ready", flush=True)


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(f) for f in (setup_java, setup_gradle, setup_sdk)]
        for future in futures:
            future.result()
    java_home = next(DEST.glob("jdk-*/bin/java.exe")).parents[1]
    for directory in ("android-user", "temp", "user-home", "avd"):
        (DEST / directory).mkdir(exist_ok=True)
    env = dict(os.environ, JAVA_HOME=str(java_home), ANDROID_HOME=str(DEST / "sdk"),
        ANDROID_USER_HOME=str(DEST / "android-user"), ANDROID_AVD_HOME=str(DEST / "avd"),
        ANDROID_EMULATOR_HOME=str(DEST / "android-user"), TEMP=str(DEST / "temp"), TMP=str(DEST / "temp"))
    env["JAVA_TOOL_OPTIONS"] = f'-Djava.io.tmpdir="{DEST / "temp"}" -Duser.home="{DEST / "user-home"}"'
    env["PATH"] = str(java_home / "bin") + os.pathsep + env["PATH"]
    manager = DEST / "sdk/cmdline-tools/16.0/bin/sdkmanager.bat"
    subprocess.run([str(manager), "platforms;android-35", "build-tools;35.0.0", "platform-tools",
        f"--sdk_root={DEST / 'sdk'}"], input="y\n" * 100, text=True, env=env, check=True)
    if not (DEST / "sdk/platforms/android-35/android.jar").exists():
        raise RuntimeError("Android SDK platform was not installed")
    if "--emulator" in sys.argv:
        subprocess.run([str(manager), "emulator", "system-images;android-30;default;x86_64",
            f"--sdk_root={DEST / 'sdk'}"], input="y\n" * 20, text=True, env=env, check=True)
    print("Android build toolchain ready", flush=True)
