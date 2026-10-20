# Model compression — 20 October 2026

ScanFace selects **8-bit weights with float32 computation** for both runtimes.
Android's model shrinks from 327.04 to **83.34 MiB** (74.52%); the Core ML package
shrinks from 326.99 to **82.54 MiB** (74.76%). Warm inference time is essentially
unchanged. Faster, more aggressive candidates exceed the preselected drift gate.

The clean Android debug APK is **146.74 MiB**, down from 390.43 MiB. The compiled
Core ML resource is **82.574 MiB** in both simulator and unsigned device apps,
so the model-size reduction survives shipping-format compilation.

The [selection lock](../models/selected-models.json) pins the recipes, versions,
complete artifact hashes and predictions. The original float32 models and
[conversion reference](../models/conversion-validation.json) are retained for
comparison. Only the selected compressed model goes into each app.

## What was compared

All model tooling ran in the existing **Conda `dl` environment**. These are
post-training, data-free compression methods: there was no fine-tuning, and no
test portrait was used for calibration or optimization. Each candidate was
evaluated on the same 22 hash-pinned author synthetic face crops and two
mathematical patterns. Missing portraits make the scripts fail.

The engineering screening gate, chosen before candidate selection, is mean
absolute drift **≤0.10 years** and maximum absolute drift **≤0.25 years** on the
22 face crops, with finite outputs inside the app's 0–130 range on all 24 inputs.
Drift is relative to the pinned PyTorch float32 predictions; float32 mobile
baseline differences are below 0.0001 years. The two patterns are reported
separately and are not treated as facial-age accuracy evidence. A candidate must
pass on the backends used by the app, not just the conversion host.

This is a small synthetic numerical regression set, **not clinical validation**
or a representative demographic/photographic evaluation. Native face detection
and alignment are unchanged; their substitution for the paper's RetinaFace
pipeline remains a separate limitation. No claim about health accuracy follows
from low quantization drift. The weights retain CC BY-NC-SA 4.0 and attribution.

## Android: LiteRT 2.3.0

The actual Android `CompiledModel` API ran on the arm64 API 37
`ScanFace_Model_QA` emulator, with the same CPU accelerator and two threads as
the app. The emulator was hosted on an Apple M4 Pro with 48 GB RAM. These are
emulator measurements, not physical Android handset results.

| Candidate | MiB | Face mean drift (years) | Face max drift (years) | Warm median (ms) | Warm p90 (ms) | Selection |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Float32 baseline | 327.04 | 0.000012 | 0.000031 | 51.327 | 52.118 | Reference |
| Float16 weights | 163.95 | 0.001039 | 0.004349 | 50.752 | 51.656 | Passes, larger |
| **Int8 weights, float32 compute** | **83.34** | **0.046233** | **0.166481** | **51.425** | **52.282** | **Selected** |
| Dynamic int8 | 83.30 | 0.103555 | 0.328089 | 13.121 | 13.581 | Fails drift gate |
| Int4 weights, float32 compute | 42.56 | 1.027757 | 3.281891 | 96.622 | 98.821 | Fails drift gate |
| Dynamic int4, block size 32 | 48.63 | 0.310623 | 1.286377 | 17.384 | 17.985 | Fails drift gate |

The selected model uses per-channel symmetric int8 weights with explicit
dequantization and float computation. Its host LiteRT predictions and Android
predictions differ by at most 0.000054 years. Dynamic int8 has more substantial
host/native variation, reinforcing the need for the native check.

Timing used three rounds with candidate order rotated between rounds. Each
round creates a new model and buffers, records its first inference, performs
five warmups, then measures 25 predictions of the same `author-03` crop. The
table pools 75 warm samples per candidate. Tensor write/run/read is timed;
face detection, alignment, disk input reads and hashing are excluded. Buffers
are reused. All 24 inputs are separately checked for prediction drift.

Median model construction was 187.94 ms for float32 and 262.75 ms for selected
int8 weights; first inference was 51.88 and 54.44 ms respectively. File pages
were already read for SHA verification, so these are **not cold-storage startup
measurements**. Weight compression does not establish lower peak RAM: weights
can be expanded/dequantized for computation. No conversion or build ran during
the timed window.

Full evidence: [native timings and predictions](../models/benchmarks/quantization-2026-10-20/android-native.json),
[input/model hashes](../models/benchmarks/quantization-2026-10-20/android-input-manifest.json),
and [host LiteRT evaluation](../models/benchmarks/quantization-2026-10-20/litert-evaluation.json).

## Core ML: iOS 17-compatible packages

Core ML measurements used macOS 27.0.1 on the same M4 Pro, through
`coremltools` 9.0's synchronous `MLModel.predict`. Both `CPU_ONLY` and `ALL`
compute requests were evaluated: the app requests CPU on the simulator and
ALL on devices. ALL permits runtime hardware selection; it does not establish
that any particular operation ran on the Neural Engine. These are **Mac host
timings**, not iPhone timings, and cannot be directly compared with the Android
emulator numbers above.

| Candidate | MiB | CPU face mean / max drift (years) | ALL face mean / max drift (years) | CPU median (ms) | ALL median (ms) | Selection |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Float32 baseline | 326.99 | <0.0001 / <0.0001 | <0.0001 / <0.0001 | 9.104 | 4.554 | Reference |
| Float16 weights and compute | 163.57 | 0.104567 / 0.491432 | 0.011584 / 0.040863 | 5.583 | 4.570 | Fails CPU gate |
| Int8 weights, float16 compute | 82.16 | 0.117387 / 0.491432 | 0.042930 / 0.165863 | 5.331 | 17.882 | Fails CPU gate |
| 4-bit palette, float16 compute | 41.19 | 0.474083 / 1.801796 | 0.452883 / 1.636871 | 5.069 | 4.419 | Fails both gates |
| **Int8 weights, float32 compute** | **82.54** | **0.048133 / 0.150932** | **0.048132 / 0.150948** | **9.111** | **4.732** | **Selected** |

The selected recipe is per-channel symmetric linear int8 weight quantization,
with `weight_threshold=2048`, applied to the float32 graph. The iOS 17
`constexpr_affine_dequantize` operation preserves float computation. Smaller
weights remain uncompressed. The tested 4-bit palette uses per-tensor scalar
k-means palettes. All exports retain specification version 8, the CoreML7 opset,
and float32 input/output tensors; no iOS 18-only int4 linear/grouped compression
is used. See Apple's [compression overview](https://apple.github.io/coremltools/docs-guides/source/opt-overview.html)
and [palettization compatibility](https://apple.github.io/coremltools/docs-guides/source/opt-palettization-overview.html).

Each compute-unit/candidate pair has 12 warmups followed by three rounds over
all 24 inputs (72 timed predictions), rotating input order each round. Timings
include the Python/Core ML input/output bridge and exclude model loading,
detection and resizing. Core ML manages thread count. Selected CPU p90 is
9.444 ms versus 9.416 ms baseline; ALL p90 is 4.937 versus 4.856 ms. This shows no
speed improvement from the selected compression. Loading measurements use
existing compiler caches and are descriptive, not first-install startup costs.
The entire timing phase ran separately from other conversion/build/benchmark
workloads.

Full evidence: [all predictions and contracts](../models/benchmarks/quantization-2026-10-20/coreml-evaluation.json),
[raw timings](../models/benchmarks/quantization-2026-10-20/coreml-timing.json), and
[byte-identical reproduction check](../models/benchmarks/quantization-2026-10-20/coreml-reproduction.json).
Core ML package UUIDs and protobuf map serialization are normalized
deterministically. The measured graph/weight content and all 24 predictions
remain identical after normalization; reports preserve the pre-normalization
file hashes as well as the final installable hashes.
An [isolated fresh-baseline check](../models/benchmarks/quantization-2026-10-20/coreml-fresh-baseline.json)
also re-exported the original checkpoint and reproduced every selected package
byte; `dl`'s dependency check passed.

## Reproduce and install

Follow [model setup](models.md#prepare-the-mobile-artifacts) for the selected
models. To regenerate the complete experiment matrix, after preparing the
float32 baselines and all 22 portraits:

```sh
conda run -n dl python scripts/models/quantize_litert.py --phase generate
conda run -n dl python scripts/models/quantize_coreml.py --phase build
conda run -n dl python scripts/models/quantize_coreml.py --phase evaluate
# Run timing separately, on an otherwise idle host:
conda run -n dl python scripts/models/quantize_coreml.py --phase benchmark
```

The optional LiteRT `--phase benchmark` measures the Python host interpreter;
the Android numbers in this report instead come from the opt-in native test.
Create its private fixture bundle with:

```sh
conda run -n dl python scripts/models/prepare_native_benchmark.py \
  --model fp32=artifacts/models/fahr_faceage.tflite \
  --model fp16=artifacts/models/quantization/litert/fp16.tflite \
  --model int8-weight=artifacts/models/quantization/litert/int8-weight.tflite \
  --model int8-dynamic=artifacts/models/quantization/litert/int8-dynamic.tflite \
  --model int4-weight=artifacts/models/quantization/litert/int4-weight.tflite \
  --model int4-block32=artifacts/models/quantization/litert/int4-block32.tflite
```

Copy the generated `manifest.json`, `inputs/` and model files into the debug
app's private `files/model-benchmark/` directory using `adb run-as`. Install the
debug app and its instrumentation APK, then run:

```sh
adb shell am instrument -w -r \
  -e class com.scanface.ml.FahrQuantizationBenchmark \
  -e scanfaceBenchmark true \
  com.scanface.app.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.scanface.app cat \
  files/model-benchmark/native-android.json > native-android.json
```

The benchmark is excluded from ordinary Gradle test runs and requires explicit
opt-in when invoked directly. Its large private candidate files
are test-only and should be removed after use; none are shipping resources.
Regenerated reports stay in `artifacts/models/quantization/` until reviewed.
Selection changes require an explicit reviewed lock update. Installation
rehashes all files, validates the recorded drift and tensor/opset contracts,
stages complete model directories and rolls back on failure. Build guards and
runtime metadata checks prevent silently using a different model profile.

Native regression tests compare the installed selection against its own locked
gradient/gray predictions with the existing 0.02-year execution tolerance. They
also exercise actual face detection, alignment and inference. Original float32
fixtures and their original tolerance are retained unchanged. Physical handset
latency, memory, battery and broader prediction validation remain future work.

After installation, all 10 Android native/UI regressions, 23 iOS native tests
and 8 installer safety tests passed. Android lint and clean debug build, iOS
simulator and unsigned device builds, and negative profile/hash build checks
also passed. iOS 17 remains the minimum deployment target.
