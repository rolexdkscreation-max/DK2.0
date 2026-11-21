// DK backend की सारी बदलने लायक संख्याएँ और क्रम - सिर्फ़ यही एक जगह.
// (मालिक: "दोनों संख्याएँ एक जगह रखो, ताकि मैं बदल सकूँ")

/** बिना लाइसेंस वाला मेहमान: रोज़ बोलने का कुल समय (सेकंड) और टेक्स्ट चैट संदेश. दिन भारतीय समय से बदलता है. */
export const FREE_VOICE_SECONDS = 600;
export const FREE_CHAT_MESSAGES = 30;
/** इतने सेकंड बचें तो DK चेतावनी दे */
export const WARN_SECONDS_LEFT = 60;

/**
 * मालिक (18 नवंबर 2026): "मुफ़्त वाला अपनी Gemini key डालेगा, उसके बाद ही 10 मिनट वाली सेवा - सब उसी की key पे".
 * false = बिना लाइसेंस वाले को सर्वर की AI keys (/ai/chat, /stt, /live/token) नहीं मिलतीं - वह अपनी key से चलता है,
 * सर्वर सिर्फ़ उसकी आवाज़ वाली बात का रोज़ का समय गिनता है (/voice/grant). true = पहले जैसा (सर्वर की keys से मुफ़्त सीमा).
 * लाइसेंस वाले और मालिक पर इसका कोई असर नहीं.
 */
export const FREE_SERVER_AI = false;

/** स्तर - बड़ा अंक = ज़्यादा सुविधा */
export const LEVELS = { free: 0, basic: 1, premium: 2, owner: 3 };

/**
 * हर काम का न्यूनतम स्तर. /ai/chat में ऐप `task` भेजता है.
 * free = सिर्फ़ बातचीत (चैट, आवाज़, Live) - रोज़ की सीमा के साथ.
 * basic = बातचीत + खोज + डीप रिसर्च, बिना रोज़ की सीमा.
 * premium = सब कुछ (कोडिंग एजेंट, DK Computer, बाकी भारी काम).
 */
export const TASK_LEVEL = {
  chat: "free",
  voice: "free",
  live: "free",
  search: "basic",
  research: "basic",
  coder: "premium",
  computer: "premium",
  heavy: "premium",
};

/** लाइसेंस/स्तर की जाँच कितनी देर याद रहे */
export const LEVEL_CACHE_MS = 3 * 60_000;
/** Google की public keys (Cache-Control न मिले तो) कितनी देर याद रहें */
export const JWKS_CACHE_MS = 6 * 3_600_000;
/** fallback में हर मॉडल को पहला शब्द भेजने का समय */
export const MODEL_TIMEOUT_MS = 4_000;
/** आवाज़ वाली बातचीत का टिकट (/stt के बाद /ai/chat) कितनी देर चले */
export const VOICE_TICKET_MS = 2 * 60_000;
/** /stt में एक बार में अधिकतम आवाज़ (बाइट) - 16 kHz WAV ≈ 60 सेकंड */
export const STT_MAX_BYTES = 2_000_000;

/**
 * DK लाइव (Gemini Live) बिना अपनी key: ऐप सीधे Google से WebSocket पर जुड़ता है, पर सर्वर की key की जगह
 * छोटी मियाद वाला टिकट (ephemeral token) - असली key कभी फ़ोन पर नहीं जाती, Worker पर आवाज़ का बोझ नहीं (मुफ़्त plan).
 * मुफ़्त वाले: हर टिकट इतने सेकंड का (उतना समय पहले ही गिना जाता है; मियाद पर Google खुद रोकता है).
 * लाइसेंस/मालिक: टिकट इतने मिनट का (फिर नया).
 */
export const LIVE_FREE_BLOCK_SECONDS = 60;
export const LIVE_PAID_MINUTES = 30;
export const LIVE_TOKEN_URL = "https://generativelanguage.googleapis.com/v1beta/auth_tokens";
export const LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained";

/** Whisper (Cloudflare Workers AI) - नाम बदले तो सिर्फ़ यहाँ */
export const STT_MODEL = "@cf/openai/whisper-large-v3-turbo";

/**
 * 20 नवंबर 2026 (मालिक): इमेज = सिर्फ़ Cloudflare Workers AI का FLUX-1 schnell (कोई दूसरी कंपनी / fallback नहीं), सिर्फ़ मालिक.
 * मुफ़्त: रोज़ 10,000 Neurons, 00:00 UTC (भारत सुबह 5:30) पर नया. Cloudflare का भाव: 512x512 के हर टुकड़े पर 4.8 + हर step पर 9.6.
 * 1024x1024 = 4 टुकड़े, 4 steps: 4 x 4.8 + 4 x 4 x 9.6 = 172.8 Neurons => लगभग 57 इमेज रोज़ (Whisper भी इसी में से खाता है - इसलिए "लगभग").
 */
export const IMAGE_MODEL = "@cf/black-forest-labs/flux-1-schnell";
export const IMAGE_STEPS = 4;
export const IMAGE_PROMPT_MAX = 2048;
export const DAILY_NEURONS = 10_000;
export const NEURONS_PER_IMAGE = 172.8;

/**
 * /ai/chat का क्रम. हर कंपनी तभी आज़माई जाती है जब उसकी key Worker secret में हो.
 * मालिक का क्रम: Gemini → हल्का Gemini → Mistral, फिर बाकी सारी कंपनियाँ जो ऐप में भी हैं.
 * kind: gemini = Google का अपना format, openai = OpenAI जैसा /chat/completions.
 */
export const AI_CHAIN = [
  { id: "gemini", kind: "gemini", secret: "GEMINI_API_KEY", model: "gemini-3.8-flash" },
  { id: "gemini-lite", kind: "gemini", secret: "GEMINI_API_KEY", model: "gemini-3.5-flash-lite" },
  { id: "mistral", kind: "openai", secret: "MISTRAL_API_KEY", base: "https://api.mistral.ai/v1", model: "mistral-small-latest" },
  { id: "groq", kind: "openai", secret: "GROQ_API_KEY", base: "https://api.groq.com/openai/v1", model: "openai/gpt-oss-120b" },
  { id: "openrouter", kind: "openai", secret: "OPENROUTER_API_KEY", base: "https://openrouter.ai/api/v1", model: "openrouter/free" },
  { id: "zai", kind: "openai", secret: "ZAI_API_KEY", base: "https://api.z.ai/api/paas/v4", model: "glm-4.7-flash" },
  { id: "nvidia", kind: "openai", secret: "NVIDIA_API_KEY", base: "https://integrate.api.nvidia.com/v1", model: "meta/llama-3.3-70b-instruct" },
  // ऐप के "मज़बूत मॉडल" वाली कंपनियाँ (paid key) - मालिक secret डाले तभी, सबसे आख़िर में (मुफ़्त वाले पहले)
  { id: "deepseek", kind: "openai", secret: "DEEPSEEK_API_KEY", base: "https://api.deepseek.com", model: "deepseek-v4-pro" },
  { id: "openai", kind: "openai", secret: "OPENAI_API_KEY", base: "https://api.openai.com/v1", model: "gpt-6-astra" },
  { id: "anthropic", kind: "openai", secret: "ANTHROPIC_API_KEY", base: "https://api.anthropic.com/v1", model: "claude-sonnet-5-5" },
];

/**
 * DK LLM (कदम 1): रोज़ की आम बातचीत पहले DK के अपने सर्वर-मॉडल पर (Cloudflare Workers AI, कोई नई key नहीं - `AI` binding).
 * भारी / ताज़ा जानकारी वाले सवाल (खबर, खोज, कोड, लंबा लेख...) सीधे ऊपर वाली बड़ी कड़ी पर.
 * DK LLM न चले (सीमा 4006, गलती, 4 सेकंड में पहला शब्द नहीं) तो अपने-आप बड़ी कड़ी पर.
 * कदम 2: DK की बातचीत से बना LoRA यहाँ `lora` में (नाम/ID) - Worker var `DK_LLM_LORA` से भी.
 * बंद करना हो: Worker var `DK_LLM = "off"`.
 */
export const DK_LLM = { id: "dk", kind: "workers", model: "@cf/google/gemma-4-26b-a4b-it", lora: "", maxTokens: 1024 };
/** इससे लंबा सवाल = भारी काम, बड़ा मॉडल */
export const DK_LLM_MAX_CHARS = 600;
/** पूरी बातचीत इससे लंबी हो तो भी बड़ा मॉडल */
export const DK_LLM_MAX_CONTEXT = 12_000;
/** इन कामों (task) पर DK LLM कभी नहीं - सिर्फ़ बातचीत वाले */
export const DK_LLM_TASKS = ["chat", "voice", "live"];
/** इनमें से कुछ भी दिखे तो बड़ा मॉडल: ताज़ा जानकारी, खोज, कोड, लंबा लिखना, link */
export const DK_HEAVY_WORDS = [
  "news", "खबर", "ख़बर", "समाचार", "न्यूज़", "न्यूज", "latest", "ताज़ा", "ताजा", "breaking",
  "search", "खोज", "सर्च", "google", "गूगल", "internet", "इंटरनेट",
  "price", "कीमत", "क़ीमत", "भाव", "rate", "रेट", "stock", "शेयर", "sensex", "nifty", "bitcoin",
  "score", "स्कोर", "match", "मैच", "weather", "मौसम", "election", "चुनाव",
  "code", "कोड", "program", "प्रोग्राम", "script", "function", "bug", "error", "app बना", "website",
  "essay", "निबंध", "लेख", "article", "report", "रिपोर्ट", "summary", "सारांश", "summarize",
  "research", "रिसर्च", "detail", "विस्तार", "पूरी जानकारी", "compare", "तुलना",
  "http", "www.", ".com",
];
/** DK LLM को हर बार साथ में - ऐप का अपना system इसके बाद आता है */
export const DK_LLM_PERSONA =
  "तुम DK हो - मालिक का अपना निजी सहायक. छोटे, साफ़, दोस्ताना जवाब दो, उसी भाषा में जिसमें सवाल है (हिंदी / Hinglish / English). " +
  "जो पक्का न पता हो, वह मत बनाओ - साफ़ कहो कि पता नहीं.";

/** /search का क्रम: Tavily → Exa → Linkup → Firecrawl → Google (Gemini की Google Search) - जिसकी key हो (ऐप की सारी खोज कंपनियाँ) */
export const SEARCH_CHAIN = ["tavily", "exa", "linkup", "firecrawl", "google"];
/**
 * 18 नवंबर 2026 (मालिक: "डीप रिसर्च में Google नहीं, सीधे Exa / Tavily से"): गहरी खोज (deep: true) का क्रम.
 * Gemini की Google Search मुफ़्त key पर चलती ही नहीं (429) - इसलिए इसमें नहीं.
 */
export const RESEARCH_CHAIN = ["tavily", "exa", "linkup", "firecrawl"];
/** /search/read: एक बार में ज़्यादा से ज़्यादा इतने पेज, हर पेज का इतना पाठ */
export const READ_MAX_URLS = 8;
export const READ_MAX_CHARS = 12_000;
export const READ_TIMEOUT_MS = 20_000;
export const SEARCH_GOOGLE_MODEL = "gemini-3.5-flash-lite";
export const SEARCH_TIMEOUT_MS = 8_000;

/** Worker secrets के नाम - deploy workflow सिर्फ़ भरे हुए भेजता है */
export const SECRET_NAMES = [
  "ALLOWED_EMAILS", "WEBHOOK_SECRET",
  "GEMINI_API_KEY", "MISTRAL_API_KEY", "GROQ_API_KEY", "OPENROUTER_API_KEY", "ZAI_API_KEY", "NVIDIA_API_KEY",
  "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "ANTHROPIC_API_KEY",
  "TAVILY_API_KEY", "EXA_API_KEY", "LINKUP_API_KEY", "FIRECRAWL_API_KEY",
];
