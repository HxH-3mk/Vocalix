import os
import sys
import subprocess
import zipfile
import shutil

# Detect Android SDK directory
sdk_candidates = [
    os.environ.get("ANDROID_HOME", ""),
    os.environ.get("ANDROID_SDK_ROOT", ""),
    os.path.expanduser("~/AppData/Local/Android/Sdk"),
    os.path.expanduser("~/Library/Android/sdk"),
    os.path.expanduser("~/Android/Sdk"),
    r"C:\Users\sohib\AppData\Local\Android\Sdk"
]

SDK_DIR = None
for candidate in sdk_candidates:
    if candidate and os.path.isdir(candidate):
        SDK_DIR = candidate
        break

if not SDK_DIR:
    print("Error: Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.")
    sys.exit(1)

# Find latest build-tools
build_tools_root = os.path.join(SDK_DIR, "build-tools")
if not os.path.exists(build_tools_root):
    print("Error: build-tools directory not found in SDK.")
    sys.exit(1)

available_versions = sorted(os.listdir(build_tools_root), reverse=True)
if not available_versions:
    print("Error: No build-tools version found in SDK.")
    sys.exit(1)

BUILD_TOOLS = os.path.join(build_tools_root, available_versions[0])

# Find platforms android.jar (target 34 or latest available)
platforms_root = os.path.join(SDK_DIR, "platforms")
platform_versions = sorted(os.listdir(platforms_root), reverse=True) if os.path.exists(platforms_root) else []
if "android-34" in platform_versions:
    PLATFORM_JAR = os.path.join(platforms_root, "android-34", "android.jar")
elif platform_versions:
    PLATFORM_JAR = os.path.join(platforms_root, platform_versions[0], "android.jar")
else:
    print("Error: No Android platform SDK found in platforms directory.")
    sys.exit(1)

is_windows = os.name == 'nt'
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe" if is_windows else "aapt2")
D8 = os.path.join(BUILD_TOOLS, "d8.bat" if is_windows else "d8")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe" if is_windows else "zipalign")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat" if is_windows else "apksigner")

# Keystore configuration - uses environment or creates a local debug keystore
KEYSTORE = os.environ.get("KEYSTORE_PATH", "debug.keystore")
KS_PASS = os.environ.get("KEYSTORE_PASS", "android")
KEY_ALIAS = os.environ.get("KEY_ALIAS", "androiddebugkey")
KEY_PASS = os.environ.get("KEY_PASS", "android")

# If keystore doesn't exist, generate standard debug keystore
if not os.path.exists(KEYSTORE):
    print("Creating debug keystore for signing...")
    subprocess.check_call([
        "keytool", "-genkeypair", "-v",
        "-keystore", KEYSTORE,
        "-alias", KEY_ALIAS,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-storepass", KS_PASS,
        "-keypass", KEY_PASS,
        "-dname", "CN=Vocalix, OU=Mobile, O=Vocalix, L=Global, S=Global, C=US"
    ])

for d in ["compiled_res", "gen", "bin", "dex_out"]:
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d, exist_ok=True)

if os.path.exists("compiled_res.zip"):
    os.remove("compiled_res.zip")
if os.path.exists("base.apk"):
    os.remove("base.apk")

print("1. Compiling resources...")
subprocess.check_call([AAPT2, "compile", "--dir", "res", "-o", "compiled_res.zip"])

print("2. Linking resources with targetSdkVersion 34...")
subprocess.check_call([
    AAPT2, "link",
    "-I", PLATFORM_JAR,
    "--manifest", "AndroidManifest.xml",
    "--min-sdk-version", "26",
    "--target-sdk-version", "34",
    "--java", "gen",
    "-o", "base.apk",
    "compiled_res.zip",
    "--auto-add-overlay"
])

print("3. Compiling Java sources...")
java_files = []
for root, _, files in os.walk("src"):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))
for root, _, files in os.walk("gen"):
    for f in files:
        if f.endswith(".java"):
            java_files.append(os.path.join(root, f))

subprocess.check_call([
    "javac",
    "-source", "8",
    "-target", "8",
    "-encoding", "UTF-8",
    "-cp", PLATFORM_JAR,
    "-d", "bin"
] + java_files)

print("4. Dexing with d8...")
class_files = []
for root, _, files in os.walk("bin"):
    for f in files:
        if f.endswith(".class"):
            class_files.append(os.path.join(root, f))

subprocess.check_call([
    D8,
    "--lib", PLATFORM_JAR,
    "--min-api", "26",
    "--output", "dex_out"
] + class_files)

print("5. Packaging classes.dex into base.apk...")
with zipfile.ZipFile("base.apk", "a") as z:
    z.write(os.path.join("dex_out", "classes.dex"), "classes.dex")

print("6. Zipalign...")
if os.path.exists("aligned.apk"):
    os.remove("aligned.apk")
subprocess.check_call([ZIPALIGN, "-f", "4", "base.apk", "aligned.apk"])

print("7. Signing APK...")
subprocess.check_call([
    APKSIGNER, "sign",
    "--ks", KEYSTORE,
    "--ks-pass", f"pass:{KS_PASS}",
    "--ks-key-alias", KEY_ALIAS,
    "--key-pass", f"pass:{KEY_PASS}",
    "--v1-signing-enabled", "true",
    "--v2-signing-enabled", "true",
    "--v3-signing-enabled", "true",
    "--out", "Vocalix.apk",
    "aligned.apk"
])

if os.path.isdir(".."):
    try:
        shutil.copy("Vocalix.apk", os.path.join("..", "Vocalix.apk"))
    except Exception:
        pass

print("SUCCESS: Vocalix.apk built successfully!")
