# असली बोली (LibriSpeech dev-clean, पूरी) -> 16-frame खिड़कियाँ = गलत-जागना रोकने वाले negative
import os, glob, numpy as np, soundfile as sf
from openwakeword.utils import AudioFeatures
M = os.path.expanduser("~/wk/models")
F = AudioFeatures(f"{M}/melspectrogram.onnx", f"{M}/embedding_model.onnx", ncpu=2)
files = sorted(glob.glob(os.path.expanduser("~/wk/data/LibriSpeech/dev-clean/*/*/*.flac")))
out = []
rng = np.random.default_rng(1)
for f in files:
    a, sr = sf.read(f, dtype="float32")
    a = a / (np.abs(a).max() + 1e-6) * rng.uniform(0.1, 0.9)
    x = (np.concatenate([np.zeros(8000, np.float32), a]) * 32767).astype(np.int16)
    e = F._get_embeddings(x)          # [frames, 96]
    if len(e) < 16: continue
    w = np.lib.stride_tricks.sliding_window_view(e, (16, 96))[::2, 0]
    out.append(w.astype(np.float16))
arr = np.concatenate(out); np.save(os.path.expanduser("~/wk/feat/train_libri.npy"), arr); print("libri", arr.shape, flush=True)
