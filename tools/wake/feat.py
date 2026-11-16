# हे डीके / हेलो डीके: wav -> openWakeWord embedding windows [16,96]
import os, sys, glob, random, numpy as np, soundfile as sf
from scipy.signal import fftconvolve
from openwakeword.utils import AudioFeatures
random.seed(3); np.random.seed(3)
M = os.path.expanduser("~/wk/models"); G = os.path.expanduser("~/wk/gen"); OUT = os.path.expanduser("~/wk/feat"); os.makedirs(OUT, exist_ok=True)
F = AudioFeatures(f"{M}/melspectrogram.onnx", f"{M}/embedding_model.onnx", ncpu=2)
L = 32000  # 2.0 s window -> 16 embeddings

def load(p):
    a, sr = sf.read(p, dtype="float32")
    if a.ndim > 1: a = a.mean(1)
    if sr != 16000:
        from scipy.signal import resample_poly; a = resample_poly(a, 16000, sr).astype("float32")
    return a

def trim(a, th=0.02):
    e = np.abs(a); idx = np.where(e > th * max(e.max(), 1e-4))[0]
    return a[max(0, idx[0] - 800): idx[-1] + 800] if len(idx) else a

NOISE = []
def noise_pool():
    files = glob.glob(os.path.expanduser("~/wk/data/LibriSpeech/dev-clean/*/*/*.flac"))
    random.shuffle(files)
    for f in files[:300]:
        NOISE.append(("speech", load(f)))
    for _ in range(60):
        w = np.random.randn(16000 * 6).astype("float32")
        k = random.choice(["white", "pink", "brown"])
        if k != "white":
            s = np.fft.rfft(w); fr = np.arange(len(s)) + 1
            s /= (fr ** (0.5 if k == "pink" else 1.0)); w = np.fft.irfft(s).astype("float32")
        NOISE.append((k, w / (np.abs(w).max() + 1e-6)))

def babble(n):
    out = np.zeros(n, "float32")
    sp = [x for k, x in NOISE if k == "speech"]
    for _ in range(random.randint(1, 4)):
        x = random.choice(sp)
        if len(x) < n: x = np.tile(x, n // len(x) + 1)
        st = random.randint(0, len(x) - n); out += x[st:st + n]
    return out

def noise(n):
    if random.random() < 0.6 and len(NOISE) > 60: return babble(n)
    k, x = random.choice([z for z in NOISE if z[0] != "speech"])
    if len(x) < n: x = np.tile(x, n // len(x) + 1)
    st = random.randint(0, len(x) - n); return x[st:st + n].copy()

def rir():
    rt = random.uniform(0.15, 0.7); n = int(16000 * rt)
    t = np.arange(n) / 16000; ir = np.random.randn(n) * np.exp(-6.9 * t / rt); ir[0] = 1.0
    return (ir / np.sqrt((ir ** 2).sum())).astype("float32")

def place(a, aug=True, snr=None):
    a = trim(a)
    if aug and random.random() < 0.45: a = fftconvolve(a, rir())[: len(a) + 4000].astype("float32")
    a = a[-L + 1600:] if len(a) > L - 1600 else a
    pad_after = random.randint(0, 4000)            # शब्द के खत्म होने के बाद 0-250 ms
    x = np.zeros(L, "float32"); end = L - pad_after; st = max(0, end - len(a)); x[st:end] = a[: end - st]
    pk = np.abs(x).max() + 1e-6
    x = x / pk * random.uniform(0.05, 0.9) if aug else x / pk * 0.5
    if aug and random.random() < 0.85 or snr is not None:
        s = snr if snr is not None else random.uniform(0, 20)
        nz = noise(L); ps = (x ** 2).mean() + 1e-9; pn = (nz ** 2).mean() + 1e-9
        x = x + nz * np.sqrt(ps / (pn * 10 ** (s / 10)))
    x = np.clip(x, -1, 1)
    return (x * 32767).astype(np.int16)

def emb(clips):
    X = np.stack(clips)
    e = F.embed_clips(X, batch_size=256, ncpu=2)
    return e[:, -16:, :].astype(np.float16)

def build(name, files, n_aug, aug=True, snr=None):
    out = []; batch = []
    for f in files:
        try: a = load(f)
        except Exception: continue
        if len(a) < 1600: continue
        for _ in range(n_aug): batch.append(place(a, aug, snr))
        if len(batch) >= 1024: out.append(emb(batch)); batch = []
    if batch: out.append(emb(batch))
    arr = np.concatenate(out) if out else np.zeros((0, 16, 96), np.float16)
    np.save(f"{OUT}/{name}.npy", arr); print(name, arr.shape, flush=True)

if __name__ == "__main__":
    noise_pool(); print("noise", len(NOISE), flush=True)
    which = sys.argv[1]
    g = lambda split, kind: sorted(glob.glob(f"{G}/edge/{split}/{kind}/*.wav") + glob.glob(f"{G}/piper/{split}/{kind}/*.wav"))
    if which == "train":
        for k in ["hey", "hello"]: build(f"train_{k}", g("train", k), 3)
        build("train_neg", g("train", "neg"), 3)
        # असली इंसानी बोली (अंग्रेज़ी) के टुकड़े भी गलत उदाहरण
        sp = sorted(glob.glob(os.path.expanduser("~/wk/data/LibriSpeech/dev-clean/*/*/*.flac")))
        random.shuffle(sp); build("train_speechneg", sp[:1200], 2)
    else:
        for k in ["hey", "hello"]:
            build(f"test_{k}_clean", g("test", k), 1, aug=False)
            build(f"test_{k}_noisy5", g("test", k), 1, aug=False, snr=5)
        build("test_neg", g("test", "neg"), 1, aug=False)
