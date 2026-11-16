# openWakeWord (हमारे हेड) बनाम sherpa-onnx KWS - एक ही wav पर: पहचान %, गलत जागना / घंटा, CPU समय
import os, sys, glob, time, json, numpy as np, soundfile as sf
from scipy.signal import resample_poly
H = os.path.expanduser("~/wk"); M = f"{H}/models"
which = sys.argv[1]  # oww | sherpa
fp_files = sorted(glob.glob(f"{H}/data/LibriSpeech/test-clean/*/*/*.flac"))
def load(p):
    a, sr = sf.read(p, dtype="float32")
    if a.ndim > 1: a = a.mean(1)
    if sr != 16000: a = resample_poly(a, 16000, sr).astype("float32")
    return a
pos = {w: sorted(glob.glob(f"{H}/gen/piper/test/{w}/*.wav")) + sorted(glob.glob(f"{H}/gen/edge/test/{w}/*.wav")) for w in ("hey", "hello")}
neg = sorted(glob.glob(f"{H}/gen/piper/test/neg/*.wav")) + sorted(glob.glob(f"{H}/gen/edge/test/neg/*.wav"))
pad = np.zeros(16000, np.float32)
res = {"which": which}

if which == "oww":
    from openwakeword.model import Model
    th = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
    mk = lambda: Model(wakeword_models=[f"{H}/out/hey_dk.onnx", f"{H}/out/hello_dk.onnx"], inference_framework="onnx",
                       melspec_model_path=f"{M}/melspectrogram.onnx", embedding_model_path=f"{M}/embedding_model.onnx")
    m = mk()
    def seq(a):
        m.reset(); x = (np.concatenate([pad, a, pad]) * 32767).astype(np.int16); out = {"hey_dk": [], "hello_dk": []}
        for i in range(0, len(x) - 1280, 1280):
            p = m.predict(x[i:i + 1280])
            for k in out: out[k].append(p.get(k, 0))
        return {k: np.array(v) for k, v in out.items()}
    def pmax(v, P):  # P लगातार frame में से सबसे कम का max
        if len(v) < P: return 0.0
        return float(np.lib.stride_tricks.sliding_window_view(v, P).min(1).max())
    PS = (1, 2, 3, 4)
    for w in ("hey", "hello"):
        ss = [seq(load(f))[f"{w}_dk"] for f in pos[w]]
        res[f"scores_{w}"] = {P: [pmax(v, P) for v in ss] for P in PS}
    nn = [seq(load(f)) for f in neg]
    res["scores_neg"] = {P: [max(pmax(d[k], P) for k in d) for d in nn] for P in PS}
    t0 = time.process_time(); secs = 0; allv = {"hey_dk": [], "hello_dk": []}
    m.reset()
    for f in fp_files:
        x = (load(f) * 32767).astype(np.int16); secs += len(x) / 16000
        for i in range(0, len(x) - 1280, 1280):
            p = m.predict(x[i:i + 1280])
            for k in allv: allv[k].append(p.get(k, 0))
    res["cpu_per_audio_s"] = (time.process_time() - t0) / secs; res["fp_hours"] = secs / 3600
    fh = {}
    for k, v in allv.items():
        v = np.array(v)
        for P in PS:
            r = np.lib.stride_tricks.sliding_window_view(v, P).min(1)
            for t in (0.3, 0.5, 0.55, 0.7, 0.8, 0.9):
                idx = np.where(r >= t)[0]; c = 0; last = -999
                for i in idx:
                    if i - last > 25: c += 1; last = i
                fh[f"{k}_P{P}_{t}"] = c
    res["fp_hits"] = fh
else:
    import sherpa_onnx
    d = f"{M}/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    kwf = f"{H}/gen/sherpa_kw.txt"
    raw = ["HEY DEE KAY @hey_dk", "HEY D K @hey_dk", "HELLO DEE KAY @hello_dk", "HELLO D K @hello_dk"]
    open(f"{H}/gen/sherpa_kw_raw.txt", "w").write("\n".join(raw) + "\n")
    toks = sherpa_onnx.text2token([r.split("@")[0].strip() for r in raw], tokens=f"{d}/tokens.txt", tokens_type="bpe", bpe_model=f"{d}/bpe.model")
    open(kwf, "w").write("\n".join(" ".join(t) + " @" + r.split("@")[1] for t, r in zip(toks, raw)) + "\n")
    kws = sherpa_onnx.KeywordSpotter(tokens=f"{d}/tokens.txt", encoder=f"{d}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        decoder=f"{d}/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", joiner=f"{d}/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        num_threads=1, keywords_file=kwf, keywords_score=1.0, keywords_threshold=0.25, max_active_paths=4)
    def run(a):
        s = kws.create_stream(); x = np.concatenate([pad, a, pad]); got = set()
        for i in range(0, len(x), 1600):
            s.accept_waveform(16000, x[i:i + 1600])
            while kws.is_ready(s): kws.decode_stream(s)
            r = kws.get_result(s)
            if r: got.add(r); kws.reset_stream(s)
        return got
    for w in ("hey", "hello"):
        res[f"hit_{w}"] = sum(f"{w}_dk" in run(load(f)) for f in pos[w]); res[f"n_{w}"] = len(pos[w])
    res["neg_hits"] = sum(bool(run(load(f))) for f in neg); res["n_neg"] = len(neg)
    t0 = time.process_time(); secs = 0; n = 0
    s = kws.create_stream()
    for f in fp_files:
        x = load(f); secs += len(x) / 16000
        for i in range(0, len(x), 1600):
            s.accept_waveform(16000, x[i:i + 1600])
            while kws.is_ready(s): kws.decode_stream(s)
            if kws.get_result(s): n += 1; kws.reset_stream(s)
    res["cpu_per_audio_s"] = (time.process_time() - t0) / secs; res["fp_hours"] = secs / 3600; res["fp_hits"] = n
json.dump(res, open(f"{H}/out/bench_{which}.json", "w"))
print({k: v for k, v in res.items() if not k.startswith("scores")}, flush=True)
