# Turn-end replay bench (offline evidence)

Gradle build isko compile nahi karta (yeh `app/src` ke bahar hai).

- `gen.py`: edge-tts neural voices (hi-IN Swara/Madhur, en-IN Neerja/Prabhat) se 20 utterances x slow/normal/fast = 60 bana deta hai, jinme beech mein pause hote hain (Hindi/English/Hinglish).
- `frames.py`: har utterance ko 5 conditions mein dalta hai (clean/fan/tv/gaps/soft), yaani 300 cases. Isme caption lag 400-600ms ka hai, aur 12 x 20s ke noise-only clips bhi hain.
- `Bench.kt` / `NewPol.kt`: inme repo ke asli `TurnDetector`, `TurnEnd`, `StreamEndGate` aur `SilenceStreamGate` 20ms frames par chalaye jaate hain. Server VAD ka model sirf energy-based hai (yeh ek simplification hai).
- `BEFORE.txt`: main ka code (server 500ms). `AFTER.txt`: is branch ka code (server 800ms + adaptive client).

Limitation: yeh real phone mic ya real Gemini server se nahi chala. Real device par median/p95 Settings -> latency board (LatencyBoard) mein dikhte hain.
