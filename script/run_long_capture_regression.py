"""Run the long-capture instrumentation with the accessibility service enabled after startup."""

from pathlib import Path
import argparse
import re
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
ADB = Path(r"C:\Users\ricar\Tools\scrcpy\adb.exe")
PACKAGE = "com.rimeai.keyboard.debug"
SERVICE = PACKAGE + "/com.osfans.trime.ai.AiAccessibilityCaptureService"
RUNNER = PACKAGE + ".test/com.osfans.trime.ai.AiIntegrationInstrumentation"


def adb(serial, *args):
    return subprocess.run(
        [str(ADB), "-s", serial, *args],
        capture_output=True,
        text=True,
        timeout=35,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def shell(serial, *args):
    return adb(serial, "shell", *args)


def require(result, action):
    if result.returncode != 0:
        raise RuntimeError(f"{action}: {result.stderr or result.stdout}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", default="emulator-5556")
    args = parser.parse_args()
    serial = args.serial
    require(shell(serial, "settings", "put", "secure", "accessibility_enabled", "0"), "disable accessibility")
    require(shell(serial, "settings", "delete", "secure", "enabled_accessibility_services"), "clear service")
    evidence = ROOT / "build" / "test-evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    log_path = evidence / "held-capture-instrumentation.log"
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            [str(ADB), "-s", serial, "shell", "am", "instrument", "-w", "-e", "longCapture", "true", RUNNER],
            stdout=log,
            stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            deadline = time.monotonic() + 80
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Instrumentation stopped before it was ready")
                ready = shell(serial, "run-as", PACKAGE, "cat", "files/long-capture-ready")
                if ready.returncode == 0 and re.fullmatch(r"0\.1\.\d+-ai", ready.stdout.strip()):
                    break
                time.sleep(0.4)
            else:
                raise RuntimeError("Instrumentation did not become ready")
            require(shell(serial, "settings", "put", "secure", "enabled_accessibility_services", SERVICE), "enable service")
            require(shell(serial, "settings", "put", "secure", "accessibility_enabled", "1"), "enable accessibility")
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                state = require(shell(serial, "dumpsys", "accessibility"), "read accessibility state")
                bound = state.split("Bound services:", 1)[-1].split("\n", 1)[0]
                if "Huiyi quick screenshot" in bound:
                    break
                time.sleep(0.4)
            else:
                raise RuntimeError("Accessibility service did not bind")
            require(shell(serial, "run-as", PACKAGE, "touch", "files/long-capture-go"), "release test gate")
            process.wait(timeout=900)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
    output = log_path.read_text(encoding="utf-8", errors="replace")
    print(output.strip())
    if "PASS: held-finger accessibility scrolling" not in output:
        raise RuntimeError(f"Long capture checks failed; see {log_path}")


if __name__ == "__main__":
    main()
