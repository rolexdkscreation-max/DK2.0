import os, sys, random, wave, numpy as np, json
from piper import PiperVoice, SynthesisConfig
from scipy.signal import resample_poly
random.seed(11)
P = os.path.expanduser("~/wk/data/piper"); OUT = os.path.expanduser("~/wk/gen/piper")
def save(path, audio, sr):
    if sr != 16000: audio = resample_poly(audio, 16000, sr)
    a = np.clip(audio, -1, 1); a = (a * 32767).astype(np.int16)
    with wave.open(path, "wb") as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(16000); w.writeframes(a.tobytes())
def synth(v, text, sid, ls, ns, nw):
    cfg = SynthesisConfig(speaker_id=sid, length_scale=ls, noise_scale=ns, noise_w_scale=nw)
    chunks = list(v.synthesize(text, syn_config=cfg))
    return np.concatenate([c.audio_float_array for c in chunks]), chunks[0].sample_rate
EN_POS = {"hey": ["hey dee kay", "hey D K", "hey, D.K.", "hey deekay", "hay dee kay"], "hello": ["hello dee kay", "hello D K", "hello, D.K.", "helo dee kay", "hullo dee kay"]}
HI_POS = {"hey": ["हे डीके", "हे, डी के", "हे डी के"], "hello": ["हेलो डीके", "हैलो डी के", "हेलो, डी के"]}
EN_NEG = ["hey", "hello", "hey DJ", "hey TV", "hey Siri", "okay Google", "hey baby", "decay", "hey dee", "hey kay", "hello didi", "hey buddy",
          "dee kay", "hey G K", "hey B K", "hey T K", "hey Deepak", "hey P K", "hello B K", "hey K D", "hello DJ", "hey M K", "hey there",
          "hey Vicky", "hey Disney", "they decay", "hey, take it", "today okay", "hello honey", "hello Mike", "hey Alexa", "I like D K", "the key", "hey Casey", "hey, OK"]
HI_NEG = ["हे भगवान", "हेलो दीदी", "हे भाई", "हेलो जी", "डीके", "हे टीके", "हेलो बीके", "हे दीपक", "हे केडी", "ठीक है", "हे देखो", "हे राम", "अरे दीदी", "हे जीके"]
def run(model, speakers_train, speakers_test, pos, neg, n_pos_train, n_pos_test, n_neg_train, n_neg_test):
    v = PiperVoice.load(f"{P}/{model}.onnx")
    for split, spk, npos, nneg in [("train", speakers_train, n_pos_train, n_neg_train), ("test", speakers_test, n_pos_test, n_neg_test)]:
        for kind in ["hey", "hello", "neg"]:
            d = f"{OUT}/{split}/{kind}"; os.makedirs(d, exist_ok=True)
            n = nneg if kind == "neg" else npos
            for i in range(n):
                path = f"{d}/{model}_{i}.wav"
                if os.path.exists(path): continue
                sid = random.choice(spk) if spk else None
                t = random.choice(neg if kind == "neg" else pos[kind])
                try:
                    a, sr = synth(v, t, sid, random.uniform(0.75, 1.35), random.uniform(0.4, 0.9), random.uniform(0.5, 1.0))
                    save(path, a, sr)
                except Exception as e: print("err", e)
    print("done", model, flush=True)
which = sys.argv[1]
if which == "libritts":
    sp = list(range(904)); random.shuffle(sp)
    run("en_US-libritts_r-medium", sp[:800], sp[800:], EN_POS, EN_NEG, 3000, 300, 2500, 300)
elif which == "vctk":
    sp = list(range(109)); random.shuffle(sp)
    run("en_GB-vctk-medium", sp[:90], sp[90:], EN_POS, EN_NEG, 1000, 150, 800, 150)
elif which == "hi":
    run("hi_IN-pratham-medium", None, None, HI_POS, HI_NEG, 400, 0, 300, 0)
    run("hi_IN-priyamvada-medium", None, None, HI_POS, HI_NEG, 400, 0, 300, 0)
    run("hi_IN-rohan-medium", None, None, HI_POS, HI_NEG, 0, 150, 0, 150)
