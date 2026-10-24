# FAHR-FaceAge model provenance and input contract

ScanFace's age-model source is **FAHR-FaceAge**, from Haugg, Lee, and colleagues, *Foundation artificial intelligence models for health recognition by use of face photographs (FAHR-Face): a model development and validation study*, The Lancet Digital Health (2026), [DOI 10.1016/j.landig.2026.101056](https://doi.org/10.1016/j.landig.2026.101056). [Elsevier's article metadata](https://api.elsevier.com/content/article/PII:S2589750026000798?httpAccept=text/xml) identifies the user's article PII, `S2589-7500(26)00079-8`, with that DOI. This is the ViT-based FAHR model, not the earlier 2025 Inception-ResNet FaceAge model.

The machine-readable source lock is [source-manifest.json](../models/source-manifest.json). Android runs the converted weights through LiteRT; iOS runs them through Core ML. Both detect and align a face locally before inference. The source lock describes the original checkpoint and author preprocessing; the native detector substitutions are documented below.

## Prepare the mobile artifacts

### Python environment

Install Conda and make `conda` available in your terminal. Model tooling uses
Python 3.12 and pip in an environment named `dl`; the recorded conversion ran on
Python 3.12.13. Check `conda env list`. Only if `dl` is missing, create it:

```sh
conda create -n dl python=3.12 pip
```

Skip creation for an existing Python 3.12 `dl` environment. Check its version:

```sh
conda run -n dl python --version
```

The bootstrap installs [pinned requirements](../scripts/models/requirements.txt)
into `dl`, upgrading or downgrading existing packages as needed to match those versions.

### Convert and install

The full workflow requires macOS. From the repository root, run the following
commands in order before building either app:

```sh
bash scripts/models/install_tools.sh
conda run -n dl python scripts/models/prepare_models.py --runtime both --validation-portraits
conda run -n dl python scripts/models/quantize_litert.py --candidates int8-weight
conda run -n dl python scripts/models/quantize_coreml.py --phase build --variants int8-float32
conda run -n dl python scripts/models/quantize_coreml.py --phase evaluate --variants int8-float32
conda run -n dl python scripts/models/install_quantized_models.py
```

The bootstrap provisions conversion dependencies. Converter-only packages live in the ignored `artifacts/models/converter-python` overlay so their dependencies do not require downgrading the shared `dl` environment. The conversion script adds this overlay when loading the LiteRT converter; all Python execution still uses `conda dl`. Core ML conversion and prediction checks require macOS. Use `--runtime litert` for Android-only preparation or `--runtime coreml` for iOS only.

The preparation script verifies the approximately 981 MiB checkpoint against its pinned SHA-256 before restricted deserialization, strictly loads the author model, wraps its normalization, exports float32 inference graphs, and compares outputs. Neither graph contains optimizer state. These 327 MiB baselines stay in `artifacts/models/` for comparison. The selected models use 8-bit weight compression with float32 computation: approximately 83 MiB for LiteRT and 82 MiB for Core ML. All generated weights are excluded from Git. A fresh clone must prepare them before building functional mobile apps; budget several gigabytes for conversion and build caches.

The dedicated installer verifies [selected-models.json](../models/selected-models.json), complete artifact hashes, iOS 17 compatibility, external tensor contracts and recorded prediction drift before replacing the app assets. It stages clean directories and restores previous bundles if installation fails. Baseline preparation no longer accepts `--install`, preventing an older command from replacing compressed weights while the app identifies a compressed model. Android-only preparation can omit Core ML steps and pass `--runtime litert` to both preparation and installation; iOS-only uses `--runtime coreml` and omits the LiteRT step.

| Generated material | Location |
| --- | --- |
| Downloaded checkpoint, conversion work and reports | `artifacts/models/` |
| Android inference asset | `androidApp/src/main/assets/models/fahr_faceage.tflite` |
| Android conversion report and license copies | `androidApp/src/main/assets/models/conversion.json`, `androidApp/src/main/assets/models/licenses/` |
| iOS inference package | `iosApp/ScanFace/Models/FAHRFaceAge.mlpackage` |
| iOS conversion report | `iosApp/ScanFace/Models/conversion.json` |
| iOS bundled notices | Repository `models/licenses/`, included as a resource folder by `iosApp/project.yml` |

After preparation, regenerate the iOS project with `xcodegen generate` inside `iosApp`. Xcode compiles the package into `FAHRFaceAge.mlmodelc`; the generated project includes that model, its conversion report and the attribution/licenses. Android assets are packaged by Gradle. Model weights are not downloaded by the running app, and no capture is sent to the model tooling or a remote inference service.

`--validation-portraits` downloads 22 author-supplied synthetic face crops, checked against [author-test-images.json](../models/author-test-images.json), for local conversion and quantization comparisons. Together with two generated mathematical patterns these form 24 numerical conversion cases. The portraits remain in ignored artifacts and are excluded from shipping resources. The command also writes `reference.json` and `conversion-both.json` in `artifacts/models/`, including dependency versions, output values, per-case errors and artifact hashes. Conversion rejects nonfinite outputs or a maximum absolute discrepancy above 0.02 years. These checks validate numerical conversion, not clinical accuracy or face-detector equivalence.

## Mobile runtime contract

The [conversion wrapper](../scripts/models/prepare_models.py) accepts **unnormalized RGB values**. Native code performs face detection, alignment and resizing; the exported graph transposes channels, divides by 255 and applies the author normalization. Do not apply that normalization again in the native adapter.

| Runtime | Input | Output | Model identifier |
| --- | --- | --- | --- |
| Android LiteRT 2.3.0 | `serving_default` signature, `args_0`: float32 `[1, 112, 112, 3]`, RGB NHWC, `0..255` | `output_0`: float32 `[1]`, years | `fahr-faceage-1fbb84e-litert-int8w-native-v1` |
| iOS Core ML, minimum iOS 17 | `pixels`: float32 `[1, 112, 112, 3]`, RGB NHWC, `0..255` | `age`: float32 `[1]`, years | `fahr-faceage-1fbb84e-coreml-int8w-native-v1` |

Android serializes detection and LiteRT CPU inference on a worker; iOS serializes Vision, its guarded Core Image eye fallback and Core ML inference on a worker. Vision and Core ML use CPU on the simulator and permit available compute units on devices; the Core Image fallback uses software rendering. Both validate the tensor contract and accept only finite age results within `0..130`. A missing or incompatible model produces an unavailable/failed result, never an invented estimate. Cancellation suppresses results from discarded captures, with checks before and after the synchronous Core Image fallback. The UI shows the model ID and native-preprocessing disclosure with completed scans.

## Pinned sources

| Component | Source |
| --- | --- |
| Author implementation | [AIM-Harvard/FAHR-Face at a49cb07f3e15858eab5024b00e1796a8b3cfef9e](https://github.com/AIM-Harvard/FAHR-Face/tree/a49cb07f3e15858eab5024b00e1796a8b3cfef9e) |
| Author model release | [fridolin22/FAHR-Face at 1fbb84ef25956aeeb26e9e279abda2eaeef0504c](https://huggingface.co/fridolin22/FAHR-Face/tree/1fbb84ef25956aeeb26e9e279abda2eaeef0504c) |
| Age checkpoint | [fahr_faceage.pth](https://huggingface.co/fridolin22/FAHR-Face/resolve/1fbb84ef25956aeeb26e9e279abda2eaeef0504c/fahr_faceage.pth) |
| Expected checkpoint bytes | `1028501154` |
| Expected SHA-256 | `980ad2f134c5d82e9aca39a738f88a5b10aaf15ca9a5462bf95599be023cdbba` |

The checkpoint size and digest come from [Hugging Face Git LFS metadata](https://huggingface.co/api/models/fridolin22/FAHR-Face?blobs=true); verify downloaded bytes against the lock before conversion. The `.pth` contains `model_state_dict` and optimizer state. Age inference requires only `fahr_faceage.pth`; the foundation checkpoint is not an additional runtime dependency, and the authors have not released FAHR-FaceSurvival weights. Use the age-only loading example in the [pinned model card](https://huggingface.co/fridolin22/FAHR-Face/blob/1fbb84ef25956aeeb26e9e279abda2eaeef0504c/README.md), not the combined `run.py` that also requires survival weights. Prefer the restricted PyTorch loader (`weights_only=True`) and strict state-dictionary loading.

## Licenses and attribution

The [author's licensing statement](https://github.com/AIM-Harvard/FAHR-Face/blob/a49cb07f3e15858eab5024b00e1796a8b3cfef9e/README.md#licensing) separates the following:

| Material | License | Local copy |
| --- | --- | --- |
| FAHR implementation | Apache-2.0 | [Apache-2.0.txt](../models/licenses/Apache-2.0.txt) |
| FAHR model weights | CC-BY-NC-SA-4.0 | [CC-BY-NC-SA-4.0.txt](../models/licenses/CC-BY-NC-SA-4.0.txt) |
| Journal article | CC-BY-NC-ND-4.0 | [Publisher license declaration](https://api.elsevier.com/content/article/PII:S2589750026000798?httpAccept=text/xml) |
| RetinaFace reference preprocessing code | MIT | [RetinaFace-MIT.txt](../models/licenses/RetinaFace-MIT.txt) |
| Pillow reference resampling implementation | MIT-CMU | [Pillow-MIT-CMU.txt](../models/licenses/Pillow-MIT-CMU.txt) |

The pinned FAHR repository has no standalone LICENSE or NOTICE file; its README is the declaration. The complete Apache and CC texts here are copied from the canonical licensors' URLs referenced by that declaration. [NOTICE.md](../models/licenses/NOTICE.md) records their origins and author credit. No journal article, clinical dataset, or training photos are bundled by these documents.

**The released weights do not grant commercial use.** Noncommercial redistribution requires attribution, license information, and identification of changes; distributed adaptations must retain the same or a compatible license. Converted LiteRT/Core ML and quantized weights remain derived from the FAHR weights and retain their license and attribution. Pure technical format changes need not constitute Adapted Material under section 2(a)(4), but do not remove the underlying license. Do not relabel weights under the app-code license or claim author endorsement. See the [complete CC terms](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode.en).

The authors additionally state academic research intent and prohibit discriminatory, surveillance, and harmful uses in their Ethical Use section. This documentation preserves that guidance separately from the named standard licenses. A commercial deployment needs permission beyond the released weight license. Native platform detector/SDK terms and any detector weights require their own notices; the FAHR weight license does not cover them.

## Author tensor and output contract

The [age architecture](https://github.com/AIM-Harvard/FAHR-Face/blob/a49cb07f3e15858eab5024b00e1796a8b3cfef9e/ViTForAgeEstimation.py) uses `ViTConfig(image_size=112)`, a ViT encoder without a pooling layer, and a linear head on the CLS token. Its output is one unconstrained floating-point age estimate in years. There is no confidence interval, probability, diagnostic class, or survival score in this output. Conversion wrappers can expose a one-element output tensor without changing that meaning.

The [author inference example](https://huggingface.co/fridolin22/FAHR-Face/blob/1fbb84ef25956aeeb26e9e279abda2eaeef0504c/README.md) expects:

1. Exactly one detected, cropped, aligned face, in upright RGB pixel coordinates. Camera mirroring and EXIF orientation must be resolved before detection and pixel sampling.
2. Resize the aligned face to `112 × 112` with PIL bilinear resampling. Preserve the author's rectangular crop, then resize; do not add a square padding/canonical-face warp.
3. Convert channel values to float32 and divide by `255`.
4. Normalize each RGB channel using mean `[0.485, 0.456, 0.406]` and standard deviation `[0.229, 0.224, 0.225]`.
5. Supply a `1 × 3 × 112 × 112` NCHW tensor to the original PyTorch model. The mobile graphs instead accept raw NHWC pixels and perform these tensor transformations internally, as specified in the runtime table above.

The captured full frame is not valid input merely because it can be resized to 112 pixels. The entered chronological age must not be supplied as a model input or used to adjust the predicted value.

## Face crop and rotation geometry

The pinned [author crop script](https://github.com/AIM-Harvard/FAHR-Face/blob/a49cb07f3e15858eab5024b00e1796a8b3cfef9e/other/face_crop_align.py) uses `retina-face==0.0.17`. It accepts exactly one detection, then calls `extract_faces(align=True)`. Its [extraction](https://github.com/serengil/retinaface/blob/1233104ebe368b7446a93200fc09ebeaa5ed0062/retinaface/RetinaFace.py) defaults are detection threshold `0.9`, upscaling allowed, and no bounding-box expansion. The [alignment implementation](https://github.com/serengil/retinaface/blob/1233104ebe368b7446a93200fc09ebeaa5ed0062/retinaface/commons/postprocess.py) rotates the entire image, moves the bbox center, and retains the original bbox dimensions. The nose argument is unused.

For a normal upright face, express all coordinates in original, unmirrored pixels with the origin at the top left. Sort the eye centers by horizontal position: `L` is image-left and `R` image-right. Reject missing/coincident eyes or an empty bbox. Define:

```text
theta = atan2(R.y - L.y, R.x - L.x)
C = (W / 2, H / 2)
M = [[cos(theta),  sin(theta)],
     [-sin(theta), cos(theta)]]

forward(p) = C + M * (p - C)
inverse(q) = C + transpose(M) * (q - C)

bbox = [x1, y1, x2, y2)
b = ((x1 + x2) / 2, (y1 + y2) / 2)
b_rotated = forward(b)
w = x2 - x1
h = y2 - y1

crop.left   = max(int(b_rotated.x - w / 2), 0)
crop.top    = max(int(b_rotated.y - h / 2), 0)
crop.right  = min(int(b_rotated.x + w / 2), W)
crop.bottom = min(int(b_rotated.y + h / 2), H)
```

Here `int` truncates toward zero, matching Python. Positive `theta` visually rotates counterclockwise: an eye line descending to the right becomes horizontal. Use the explicit matrix rather than assuming that each platform's rotation API has the same sign convention. Do not calculate a new bbox from the envelope of the four rotated corners: that would enlarge the crop compared with the reference.

The reference calls `PIL.Image.rotate` with its defaults: image-center rotation, no canvas expansion, nearest-neighbor sampling, black outside the image. It then slices the half-open crop, converts BGR to RGB, and finally performs the 112-pixel bilinear resize. Preserve these two resampling stages when evaluating parity; a single bilinear affine warp is not pixel-equivalent. PIL's downsampling filter support and pixel-center conventions can also differ from native bitmap resizing. The author's separate crop utility writes JPEG quality 95, while its direct inference path can use the crop in memory; record which reference path produced a fixture.

## Native detector substitution and validation

ScanFace uses [bundled Android ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection/android), package version 16.1.7, with accurate mode and eye landmarks, and [iOS Vision face landmarks](https://developer.apple.com/documentation/vision/vndetectfacelandmarksrequest), request revision 3. Both require exactly one face and both eyes, then apply the geometry above to the upright source image. The Android detector is bundled, so first use works offline. There is no fallback to estimating age from a centered full-image crop.

iOS supplies Vision with an explicit sRGB `kCVPixelFormatType_32BGRA` buffer derived from the upright source image. It first runs face-rectangle revision 3 to obtain the face bounds and pose, then supplies that observation to landmark revision 3. When the Vision eye geometry is unusable, an offline Core Image face detector may recover eye positions only if it independently finds exactly one face, reports both eyes, and its face box has intersection-over-union of at least `0.5` with the Vision box. The fallback retains the Vision bounds and the already-checked Vision pose; it does not replace the face crop with Core Image's box or relax the capture checks. Both iOS paths require eye centers inside the Vision box, spacing between `max(20 pixels, 15% of box width)` and `75% of box width`, and eye-line roll no greater than 30 degrees. A failed fallback produces no estimate.

The adapters reject faces smaller than 112 pixels or 8% of the corresponding image dimension, boxes reaching the outer 1% image boundary, missing eyes, inter-eye distance below 20 pixels, yaw/pitch above 25 degrees, or eye-line roll above 30 degrees. These are ScanFace capture-quality rules, not validated confidence scores from FAHR. They are implemented in [Android preprocessing](../androidApp/src/main/kotlin/com/scanface/ml/FacePreprocessing.kt) and [iOS preprocessing](../iosApp/ScanFace/FaceImagePreprocessor.swift).

For Vision, convert the normalized bottom-left-origin face bbox to top-left image pixels before using this geometry. For a bbox `(bx, by, bw, bh)` and a landmark `(u, v)` normalized within that bbox:

```text
image_x = (bx + u * bw) * W
image_y = (1 - by - v * bh) * H
```

Eye-region centroids or pupil landmarks are estimates of eye centers; detector names referring to the subject's left/right must not be mistaken for image-left/right. Android landmarks must likewise use the same upright image coordinate space as the sampled pixels.

Core Image's face box and eye positions use lower-left image coordinates. The fallback maps each eye `(x, y)` to `(x, H - y)` and the box's top to `H - box.maxY` before comparing it with Vision's top-left pixel bounds. Both detectors inspect the same upright image; only the accepted eye positions are substituted.

These native detectors do not reproduce RetinaFace detections, bounding boxes, or confidence calibration. The `native-v1` model IDs identify the platform-specific pipeline documented here. This is a **documented preprocessing substitution**, not validated reproduction of the study. There are two separate sources of difference: exported model outputs on identical input tensors, and end-to-end estimates from native versus author face crops. Numeric conversion agreement on 24 cases does not establish clinical accuracy or detector parity.

## What the output supports

Training optimized chronological-age labels with L1 loss. The current author release reports 743,152 development images from ten public datasets; APPA-REAL's 6,783 images were reserved for evaluation, with reported MAE 5.1 years. The earlier arXiv preprint's aggregate counts differ, so use the release's corrected development/evaluation split. [Author dataset description](https://github.com/AIM-Harvard/FAHR-Face/blob/a49cb07f3e15858eab5024b00e1796a8b3cfef9e/README.md#dataset-information)

The paper studies the association between facial-age deviation and outcomes in retrospective cancer cohorts. That does not calibrate a diagnosis, longevity score, biological-age ground truth, or expected lifespan for an individual app user. The authors identify cancer-only, mainly Western cohorts, demographic representation, image conditions, and the need for prospective validation as limitations. Small changes between photographs can reflect capture variation. [Author preprint discussion](https://arxiv.org/pdf/2506.14909)

## Derived artifact record

The generated `conversion.json` identifies the checkpoint, selected model ID, compression recipe, float32 input/output contract, file/package hashes and measured predictions. The selection lock and [quantization results](quantization.md) preserve the evaluation evidence and tool versions; the original [conversion report](../models/conversion-validation.json) remains the float32 reference. Both app bundles include the weight license and [attribution notice](../models/licenses/NOTICE.md). The selected models use 8-bit weights, dequantized for float32 computation, with the same raw-RGB NHWC normalization wrapper. A converted model is not an official author release. Native preprocessing differences remain separate from numerical model conversion and compression drift.
