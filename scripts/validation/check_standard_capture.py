# SPDX-License-Identifier: GPL-3.0-or-later
"""Run the production capture adapter against real Android MotionEvents over adb.

This standalone process uses a recording sink and does not request capture,
inject hardware events, send input to the VM, or alter the running app.
"""
import argparse
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path, required=True)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    output = repo / "build/validation/standard-capture"
    source = output / "src"
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    # Only these unrelated dependencies are replaced. The view, adapter, queue,
    # button ownership, gesture recognizer, and encoder are production sources.
    stubs = {
        "androidx/annotation/NonNull.java":
            "package androidx.annotation; public @interface NonNull {}",
        "androidx/annotation/Nullable.java":
            "package androidx.annotation; public @interface Nullable {}",
        "cn/classfun/droidvm/lib/store/vm/NativeDisplay.java":
            "package cn.classfun.droidvm.lib.store.vm; "
            "public class NativeDisplay { public static final int MOUSE=2,CHANNEL_COUNT=4; }",
        "cn/classfun/droidvm/ui/vm/display/nativedisplay/input/InputForwarder.java":
            "package cn.classfun.droidvm.ui.vm.display.nativedisplay.input; "
            "public class InputForwarder { public interface InputSink { "
            "boolean write(int channel,byte[] data); } }",
        "cn/classfun/droidvm/lib/utils/StringUtils.java":
            "package cn.classfun.droidvm.lib.utils; public class StringUtils { "
            "public static String fmt(String s,Object...args){return String.format(s,args);} }",
        "cn/classfun/droidvm/display/INativeDisplayRootService.java":
            "package cn.classfun.droidvm.display; public interface INativeDisplayRootService { "
            "boolean writeInput(String vm,String screen,int channel,byte[] data) throws Exception; }",
    }
    for relative, contents in stubs.items():
        path = source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding="utf8")
    java = repo / "app/src/main/java/cn/classfun/droidvm/ui/vm/display"
    files = list(source.rglob("*.java"))
    files.extend(Path(__file__).parent.glob("*Probe.java"))
    files.extend(java / "base" / name for name in (
        "CapturedPointerLayout.java", "StandardPointerCapture.java",
        "PointerEventQueue.java", "PointerButtons.java", "TouchpadGestureRecognizer.java",
    ))
    files.append(java / "nativedisplay/input/EvdevEncoder.java")
    files.append(java / "nativedisplay/input/DirectInputSink.java")
    subprocess.run([
        "javac", "--release", "17", "-cp", str(args.android_jar),
        "-d", str(classes), *map(str, files),
    ], check=True)
    jar = output / "classes.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes))
    dex = output / "probe.zip"
    subprocess.run([
        "java", "-cp", str(args.d8_jar), "com.android.tools.r8.D8",
        "--lib", str(args.android_jar), "--min-api", "33",
        "--output", str(dex), str(jar),
    ], check=True)
    remote = "/data/local/tmp/standard-capture-probe.zip"
    subprocess.run([args.adb, "push", str(dex), remote], check=True)
    for probe in ("StandardCaptureProbe", "CaptureRegressionProbe"):
        subprocess.run([
            args.adb, "shell", f"CLASSPATH={remote} app_process /system/bin {probe}",
        ], check=True)


if __name__ == "__main__":
    main()
