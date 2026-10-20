#!/usr/bin/env python3
"""Generate non-personal regression fixtures with the author PIL crop geometry.

Run in conda dl. Geometry follows RetinaFace 0.0.17 postprocess.py (MIT;
see models/licenses). These images are mathematical patterns, not face photos.
"""
from pathlib import Path
import json
import math
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "models/fixtures"
OUT.mkdir(parents=True, exist_ok=True)
width, height = 320, 260
y, x = np.mgrid[:height, :width]
rgb = np.stack(((3*x+y)%256, (x+2*y)%256, (7*x+5*y)%256), -1).astype(np.uint8)
box = [72, 48, 252, 228]
eyes = [[113, 100], [201, 124]]
theta = math.atan2(eyes[1][1]-eyes[0][1], eyes[1][0]-eyes[0][0])
cx, cy = (box[0]+box[2])/2-width/2, (box[1]+box[3])/2-height/2
nx = width/2 + math.cos(theta)*cx + math.sin(theta)*cy
ny = height/2 - math.sin(theta)*cx + math.cos(theta)*cy
bw, bh = box[2]-box[0], box[3]-box[1]
crop = [max(0,int(nx-bw/2)), max(0,int(ny-bh/2)), min(width,int(nx+bw/2)), min(height,int(ny+bh/2))]
aligned = Image.fromarray(rgb).rotate(math.degrees(theta), resample=Image.Resampling.NEAREST)
result = aligned.crop(crop).resize((112,112), resample=Image.Resampling.BILINEAR)
(OUT / "alignment-input.rgb").write_bytes(rgb.tobytes())
(OUT / "alignment-expected.rgb").write_bytes(np.asarray(result).tobytes())
(OUT / "alignment.json").write_text(json.dumps({
    "width": width, "height": height, "bounding_box": box, "eyes": eyes,
    "rotation_radians": theta, "rotated_crop": crop,
    "input": "alignment-input.rgb", "expected": "alignment-expected.rgb",
    "output_width": 112, "output_height": 112,
    "pixel_order": "row-major RGB uint8", "max_pixel_error": 1,
}, indent=2)+"\n")
(OUT / "inference.json").write_text(json.dumps({
    "source_checkpoint_sha256": "980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba",
    "shape": [1,112,112,3], "dtype": "float32", "range": [0,255],
    "gradient_formula": ["(3*x+y)%256", "(x+2*y)%256", "(7*x+5*y)%256"],
    "gradient_age": 5.346164703369141, "gray_127_age": 62.322818756103516,
    "absolute_tolerance_years": 0.02,
    "purpose": "Numerical regression only: these patterns are not valid face inputs.",
}, indent=2)+"\n")
print(OUT)
