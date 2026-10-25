# dk-brain (Cloudflare Worker)

v3.68: Gemini TTS ("आवाज़ इंजन 1") की चाबी ऐप में नहीं रहती - यहाँ रहती है. ऐप सिर्फ़ Worker का पता जानता है.

| रास्ता | काम |
|---|---|
| `GET /ping` | `ok` - ऐप कनेक्शन गर्म रखता है (हर टुकड़े पर नया TLS नहीं) |
| `POST /tts` `{"text","voice","style"}` | Gemini TTS (Interactions API, `stream: true`) से 16-bit PCM 24 kHz mono, आते ही आगे (`audio/l16; rate=24000`) |

- स्टाइल-निर्देश हर टुकड़े पर वही जाता है: 3.8 मॉडलों में `speech_metadata` annotation, पुराने मॉडलों में text से पहले.
- मॉडल `TTS_MODELS` के क्रम से; 404/400/429/5xx पर अगला. जो चला वही आगे पहले.

## लगाना

```bash
cd worker/dk-brain
npx wrangler deploy
npx wrangler secret put GEMINI_API_KEY      # Google AI Studio की चाबी
npx wrangler secret put APP_TOKEN           # वैकल्पिक - लगाया तो ऐप में भी वही डालें
```

फिर ऐप: सेटिंग्स -> आवाज़ -> "बोलने वाला इंजन (DK लाइव)" -> Gemini TTS -> "dk-brain Worker का पता" में
`https://dk-brain.<आपका-subdomain>.workers.dev` (और token, अगर लगाया).
