# feature-level जाँच: recall (साफ़ / 5 dB शोर), गलत जागना/घंटा (validation ~11 h), दूसरे शब्द से उलझन
import os, json, numpy as np, onnxruntime as ort
H = os.path.expanduser("~/wk"); FD = f"{H}/feat"
_v = np.load(f"{H}/data/validation_set_features.npy", mmap_mode="r"); val = np.asarray(_v[len(_v) // 2:])  # दूसरा आधा (~5.3 h) - ट्रेनिंग में कभी नहीं
def sess(w): return ort.InferenceSession(f"{H}/out/{w}_dk.onnx", providers=["CPUExecutionProvider"])
def score(s, x):
    out = []
    for i in range(0, len(x), 4096): out.append(s.run(None, {"input": np.asarray(x[i:i + 4096], np.float32)})[0][:, 0])
    return np.concatenate(out)
res = {}
TH = [0.3, 0.4, 0.5, 0.55, 0.6, 0.7, 0.8, 0.9]
for w in ("hey", "hello"):
    s = sess(w); other = "hello" if w == "hey" else "hey"; r = {}
    for k in ("clean", "noisy5"):
        sc = score(s, np.load(f"{FD}/test_{w}_{k}.npy")); r[k] = {t: float((sc >= t).mean()) for t in TH}
    sc = score(s, np.load(f"{FD}/test_{other}_clean.npy")); r["confuse_other"] = {t: float((sc >= t).mean()) for t in TH}
    sc = score(s, np.load(f"{FD}/test_neg.npy")); r["near_neg"] = {t: float((sc >= t).mean()) for t in TH}
    # validation: 16-frame खिड़की, हर frame पर
    n = len(val) - 16; win = np.lib.stride_tricks.sliding_window_view(val, (16, 96))[:, 0]
    sc = score(s, win); hours = len(val) * 0.08 / 3600
    fp = {}
    for t in TH:
        idx = np.where(sc >= t)[0]; c = 0; last = -999
        for i in idx:
            if i - last > 25: c += 1; last = i   # 2 s refractory
        fp[t] = c / hours
    r["fp_per_hour"] = fp; r["val_hours"] = hours
    res[w] = r
json.dump(res, open(f"{H}/out/eval_oww.json", "w"), indent=1)
for w in res:
    print(w, "hours", round(res[w]["val_hours"], 1))
    for t in TH: print(f"  th {t}: clean {res[w]['clean'][t]:.3f} noisy {res[w]['noisy5'][t]:.3f} other {res[w]['confuse_other'][t]:.3f} near {res[w]['near_neg'][t]:.3f} fp/h {res[w]['fp_per_hour'][t]:.2f}")
