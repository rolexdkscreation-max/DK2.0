# edge-tts से "हे डीके"/"हेलो डीके" और मिलते-जुलते गलत शब्द (train/test आवाज़ें अलग)
import asyncio, edge_tts, random, os, subprocess, sys, json
random.seed(7)
OUT = os.path.expanduser("~/wk/gen/edge")
TEST_VOICES = ["en-IN-PrabhatNeural", "hi-IN-SwaraNeural", "mr-IN-ManoharNeural", "en-US-GuyNeural", "en-US-JennyNeural", "en-GB-SoniaNeural"]
TRAIN_VOICES = ["en-IN-NeerjaNeural", "en-IN-NeerjaExpressiveNeural", "hi-IN-MadhurNeural", "mr-IN-AarohiNeural",
  "en-AU-NatashaNeural", "en-AU-WilliamMultilingualNeural", "en-GB-LibbyNeural", "en-GB-MaisieNeural", "en-GB-RyanNeural", "en-GB-ThomasNeural",
  "en-US-AnaNeural", "en-US-AndrewMultilingualNeural", "en-US-AndrewNeural", "en-US-AriaNeural", "en-US-AvaMultilingualNeural", "en-US-AvaNeural",
  "en-US-BrianMultilingualNeural", "en-US-BrianNeural", "en-US-ChristopherNeural", "en-US-EmmaMultilingualNeural", "en-US-EmmaNeural",
  "en-US-EricNeural", "en-US-MichelleNeural", "en-US-RogerNeural", "en-US-SteffanNeural"]
def texts(voice, kind):
    hi = voice.startswith(("hi-", "mr-"))
    if kind == "hey": return ["हे डीके", "हे डीके।", "हे, डी के"] if hi else ["Hey DK", "Hey D.K.", "Hey, DK!", "Hey Dee Kay"]
    if kind == "hello": return ["हेलो डीके", "हैलो डीके।", "हेलो, डी के"] if hi else ["Hello DK", "Hello D.K.", "Hello, DK!", "Hello Dee Kay"]
    neg_en = ["Hey", "Hello", "Hey DJ", "Hey TV", "Hey Siri", "OK Google", "Hey Google", "Hey baby", "Decay", "Hey Dee", "Hey Kay",
              "Hello Didi", "Hello ji", "Hey buddy", "D K", "Hey G K", "Hey BK", "Hey TK", "Hey Deepak", "Hey PK", "Hello BK", "Hey KD",
              "Hello DJ", "Hey MK", "Hey there", "Hello Deepika", "Hey Vicky", "Hey Disney", "Hey decay", "They decay", "Hey, take it", "Hey Dikshit",
              "Today okay", "Hey, the cake", "Hello, the key", "Hello honey", "Hello Mike", "Hey Alexa", "Hello Dinesh", "Hey, did you", "DK is here", "I like DK"]
    neg_hi = ["हे भगवान", "हेलो दीदी", "हे भाई", "हेलो जी", "डीके", "हे टीके", "हेलो बीके", "हे पीके", "हे दीपक", "हेलो दिनेश", "हे केडी", "ठीक है",
              "हे देखो", "हेलो डिक्की", "हे राम", "अरे दीदी", "हे दीपिका", "हेलो टीवी", "हे जीके", "ठीक है ना"]
    return neg_hi if hi else neg_en
async def one(voice, text, rate, pitch, path):
    if os.path.exists(path): return
    mp3 = path + ".mp3"
    for t in range(4):
        try:
            c = edge_tts.Communicate(text, voice, rate=f"{rate:+d}%", pitch=f"{pitch:+d}Hz")
            await c.save(mp3); break
        except Exception as e:
            await asyncio.sleep(1 + t)
    else: return
    subprocess.run(["ffmpeg", "-loglevel", "quiet", "-y", "-i", mp3, "-ar", "16000", "-ac", "1", path]); os.remove(mp3)
async def main():
    jobs = []
    for split, voices, nvar, nneg in [("train", TRAIN_VOICES, 16, 2), ("test", TEST_VOICES, 10, 2)]:
        for v in voices:
            for kind in ["hey", "hello", "neg"]:
                d = f"{OUT}/{split}/{kind}"; os.makedirs(d, exist_ok=True)
                ts = texts(v, kind)
                n = nvar if kind != "neg" else len(ts) * nneg
                for i in range(n):
                    t = ts[i % len(ts)]
                    r = random.randint(-25, 25); p = random.randint(-12, 12)
                    jobs.append(one(v, t, r, p, f"{d}/{v}_{i}.wav"))
    sem = asyncio.Semaphore(12)
    async def run(j):
        async with sem: await j
    await asyncio.gather(*[run(j) for j in jobs])
    print("edge done", len(jobs))
asyncio.run(main())
