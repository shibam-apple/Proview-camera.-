"""
Builds paired training data from RAW files + the edits you want to learn (e.g. MIT-Adobe FiveK
RAWs and Expert C TIFFs, or your own phone DNGs and your own edits):

  python prepare_pairs.py --raw ~/fivek/raw --edits ~/fivek/expertC --out data/fivek

1. Each RAW is exported to Bayer data + calibration (tools/export_raw.py).
2. The Proview Rendition renders it, exactly as the phone does (Kotlin harness via Gradle).
3. Renders go to data/fivek/inputs, matching edits (resized) to data/fivek/targets.
"""
import argparse
import os
import subprocess
import sys
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--raw", required=True)
    p.add_argument("--edits", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--size", type=int, default=768, help="long edge of saved pairs")
    a = p.parse_args()
    exported = os.path.join(a.out, "exported")
    renders = os.path.join(a.out, "renders")
    inputs = os.path.join(a.out, "inputs")
    targets = os.path.join(a.out, "targets")
    for d in (exported, renders, inputs, targets):
        os.makedirs(d, exist_ok=True)

    subprocess.check_call([sys.executable, os.path.join(HERE, "tools", "export_raw.py"), a.raw, exported])
    gradlew = os.path.join(REPO, "gradlew")
    subprocess.check_call([gradlew, "-q", "renderRaw", f"-Pin={os.path.abspath(exported)}", f"-Pout={os.path.abspath(renders)}"],
                          cwd=os.path.join(REPO, "pipeline-ref"))

    edits = {os.path.splitext(f)[0].replace(" ", "_").replace("(", "").replace(")", ""): f for f in os.listdir(a.edits)}
    n = 0
    for f in sorted(os.listdir(renders)):
        name = os.path.splitext(f)[0]
        if name not in edits:
            continue
        x = Image.open(os.path.join(renders, f)).convert("RGB")
        y = Image.open(os.path.join(a.edits, edits[name])).convert("RGB")
        if (x.width > x.height) != (y.width > y.height):
            y = y.rotate(90, expand=True)
        x.thumbnail((a.size, a.size), Image.LANCZOS)
        y = y.resize(x.size, Image.LANCZOS)
        x.save(os.path.join(inputs, name + ".png"))
        y.save(os.path.join(targets, name + ".png"))
        n += 1
    print(f"{n} pairs in {inputs} / {targets}")


if __name__ == "__main__":
    main()
