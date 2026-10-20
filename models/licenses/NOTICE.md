# Model and reference-code attribution

FAHR-Face / FAHR-FaceAge is the work of Fridolin Haugg, Grace Lee, John He, and their coauthors and contributors at AIM-Harvard. Haugg and Lee contributed equally.

Citation: Haugg F, Lee G, He J, et al. *Foundation artificial intelligence models for health recognition by use of face photographs (FAHR-Face): a model development and validation study.* The Lancet Digital Health. 2026. https://doi.org/10.1016/j.landig.2026.101056

Original code: https://github.com/AIM-Harvard/FAHR-Face/tree/a49cb07f3e15858eab5024b00e1796a8b3cfef9e

Original weights: https://huggingface.co/fridolin22/FAHR-Face/tree/1fbb84ef25956aeeb26e9e279abda2eaeef0504c

The authors declare Apache License 2.0 for code and Creative Commons Attribution-NonCommercial-ShareAlike 4.0 for model weights in the pinned repository README and model card. Their journal article has a separate CC BY-NC-ND 4.0 license. The pinned repository contains no separate LICENSE or NOTICE file; this notice records attribution and the authors' declarations without inventing a copyright holder or year.

The authors describe the software as intended for academic research, prohibit discriminatory, surveillance, and harmful applications, and ask users to follow research ethics and obtain necessary approvals. These statements are preserved as upstream guidance separately from the standard license texts.

ScanFace's converted LiteRT/Core ML or quantized model assets are derived from these original weights, not independent or officially endorsed author releases. Retain CC BY-NC-SA 4.0, this attribution, the source checkpoint identity, and a record of conversion/quantization changes with any redistributed derived assets. No commercial permission is granted by the released weight license. Native detector substitutions and their evaluation must be disclosed separately; model conversion parity does not establish clinical validation.

Reference face-cropping/alignment code: RetinaFace, Sefik Ilkin Serengil and contributors, version 0.0.17, commit `1233104ebe368b7446a93200fc09ebeaa5ed0062`. Its MIT notice is copied intact in `RetinaFace-MIT.txt`. That code license must not be assumed to license a separately obtained detector checkpoint or a native platform SDK.

Native pixel resampling follows Pillow 12.3.0 affine nearest-neighbor and separable bilinear behavior, including fixed-point rounding. Pillow/PIL attribution and MIT-CMU terms are preserved in `Pillow-MIT-CMU.txt`. The mathematical regression fixture was generated locally with Pillow; it contains no person or face photograph.

Unmodified license texts retrieved on 2026-10-20:

- `Apache-2.0.txt`: https://www.apache.org/licenses/LICENSE-2.0.txt
- `CC-BY-NC-SA-4.0.txt`: https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode.txt
- `RetinaFace-MIT.txt`: https://raw.githubusercontent.com/serengil/retinaface/1233104ebe368b7446a93200fc09ebeaa5ed0062/LICENSE
- `Pillow-MIT-CMU.txt`: https://raw.githubusercontent.com/python-pillow/Pillow/12.3.0/LICENSE

These license texts are copied for the materials identified above; they do not assign a license to unrelated ScanFace code. The research model comes without a warranty of accuracy or fitness for an individual or clinical decision. See the complete upstream licenses for their warranty and liability provisions.
