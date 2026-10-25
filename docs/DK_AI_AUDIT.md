# DK AI brief — audit checklist (Step 1)

Audit date: 24 Oct 2026, base: `main` @ `fdb913e` (DK 3.53). Owner/admin: **धीरज कुमार सिंह** (`AssistantBrand.OWNER_NAME`).

Legend: **[EXISTS-OK]** works end to end · **[EXISTS-PARTIAL]** exists but incomplete / different / not wired · **[MISSING]** not in the repo.

Important context: the existing app is a mature **View/XML + Kotlin** app (≈31,500 lines, 47 test files, package `com.dk.assistant`, AGP 8.5.2, Kotlin 1.9.24, compileSdk 34). The brief asks for Jetpack Compose with no XML layouts. Step 5 of the brief says to keep the owner's working UI, so existing screens are **not** rewritten in Compose. New features are added into the current architecture (new Compose screens only where a feature is fully new, after the Compose toolchain is added).

## Part 01 — Project setup
| Item | Status | Notes |
|---|---|---|
| Kotlin Android app, package already set | EXISTS-OK | `com.dk.assistant` is kept, not renamed to `com.dk.ai` |
| Jetpack Compose + Material3, no XML layouts | MISSING | View/XML UI (`activity_main.xml`, `activity_settings.xml`, custom Views) |
| compileSdk/targetSdk 36 | EXISTS-PARTIAL | 34 now |
| minSdk 26, Java 17 | EXISTS-OK | |
| OkHttp, coroutines, org.json | EXISTS-OK | |
| datastore, navigation-compose, webkit, splashscreen | MISSING | |
| Manifest permissions (INTERNET, RECORD_AUDIO, FGS mic, POST_NOTIFICATIONS, READ_CONTACTS, CALL_PHONE) | EXISTS-OK | |
| QUERY_ALL_PACKAGES, SYSTEM_ALERT_WINDOW | MISSING | |
| AccessibilityService, notification listener, FileProvider | EXISTS-OK | |
| ShareToDKActivity | MISSING | |
| No SEND_SMS / SmsManager | EXISTS-OK | not used |
| Release minify + shrink, signing from git-ignored file | EXISTS-PARTIAL | signing from CI secrets (`DK_KEYSTORE`), minify off |

## Part 02 — Settings, state, API keys
| Item | Status | Notes |
|---|---|---|
| AssistantBrand (NAME, FULL_NAME, TAGLINE, OWNER_NAME) | **DONE in this PR** | was hardcoded in 5 prompt strings |
| Gemini key typed in Settings, stored locally, never in code | EXISTS-OK | `Prefs` scoped keys per AI part (chat / live / coder / computer) |
| Preferences (user name, assistant name, voice, theme, language, wake word…) | EXISTS-OK | `Prefs` (SharedPreferences) |
| LanguagePreference with AUTO | **DONE in this PR** | `Lang.AUTO`, old 3 choices unchanged |
| PersonalityRepository (DataStore) + live refresh | EXISTS-PARTIAL | stored in `Prefs`; Live picks it up on the next connection, not mid-session |
| DkAppState (Compose state) | MISSING | depends on Compose |

## Part 03 — 10 personalities + Hindi/Hinglish/English brain
| Item | Status | Notes |
|---|---|---|
| Personality enum (10, instructions, safety for COMPANION/GF_MODE) | **DONE in this PR** | `Personality.kt`, picker in Settings → Profile |
| Free-text personality | EXISTS-OK | kept, now added as "extra" on top of the chosen preset |
| LanguageDetector | **DONE in this PR** | `LanguageDetector.kt` |
| Language policy in every prompt | EXISTS-OK | `Lang.sys()` on chat, Live, coder, computer |
| Conversation memory / recap | EXISTS-OK | `Memory` history (24 turns, saved), Live session resumption, `FriendTalk` recap |
| Prompt builder with personality in every brain | **DONE in this PR** | chat, chat-stream, Live, phone-agent prompts |

## Part 04 — Gemini Live client
| Item | Status | Notes |
|---|---|---|
| OkHttp WebSocket BidiGenerateContent, audio in/out, transcripts | EXISTS-OK | `GeminiLive.kt` (≈2000 lines) |
| Live model discovery with fallbacks | EXISTS-OK | `discoverModels` + `LiveModelRank` (better than one constant) |
| Tool calls + toolResponse | EXISTS-OK | 13 tools (`phone_task`, `deep_think`, `coding_task`, `computer_task`, …) |
| ToolCallDeduplicator | MISSING | |
| `build_website` tool | BUILT (Part 14) | `GeminiLive` tool -> `ForgeLauncher.fromVoice` |

## Part 05 — Mic, speaker, echo guard
| Item | Status | Notes |
|---|---|---|
| AudioRecord 16 kHz + AEC/NS/AGC | EXISTS-OK | `GeminiLive`, `Ear` |
| 24 kHz playback | EXISTS-OK | |
| Echo guard (mute mic while DK speaks) | EXISTS-PARTIAL | done differently: barge-in detector + `PhoneAudioGuard` (v3.53); no `EchoGuard` counter object |
| Mic mute button | EXISTS-PARTIAL | `GeminiLive.muteMic()` exists; the UI button on branches `feature/dk-gemini-1024-1925` / `feature/dk-1024-1428` does not compile |

## Part 06 — Voice session, reconnect, background
| Item | Status | Notes |
|---|---|---|
| Session manager independent of screens | EXISTS-OK | runs inside `DKAccessibilityService` + `DKState` |
| Auto-reconnect with backoff | EXISTS-OK | in `GeminiLive.run()` |
| Foreground service with notification | EXISTS-OK | `KeepAliveService` |
| Latency tracking | EXISTS-OK | `VoiceTurn` timeline, `LiveModelRank` latency |
| refreshInstructions mid-session | MISSING | |

## Part 07 — Phone actions
| Item | Status | Notes |
|---|---|---|
| Open apps (label matching) | EXISTS-OK | `AppLauncher` |
| Call contact (ACTION_CALL / dial) | EXISTS-OK | `PhoneActions` |
| Message via SMS/WhatsApp composer + auto-tap Send | EXISTS-OK | via the screen agent (`AgentRunner`) |
| Read screen / tap / type / scroll / back / home | EXISTS-OK | `DKAccessibilityService.readScreen()/perform()` |

## Part 08 — Text chat
| Item | Status | Notes |
|---|---|---|
| Typed chat with the same personality | EXISTS-OK | `ChatActivity` + `BrainManager.streamChat` (several AI providers, not only Gemini) |
| Copy / retry / speak, streaming reveal | EXISTS-OK | `DKChatUi` |
| Forge intent from chat | BUILT (Part 14) | `ForgeIntent` in `AgentRunner.execute` (chat + voice) |

## Part 09–11b — Design system, 3D orb, screens
| Item | Status | Notes |
|---|---|---|
| Theme / glass design | EXISTS-PARTIAL | own "liquid glass" design (`Glass`, `LiquidGlass`, `Shapes`), not the "pearl of light" tokens |
| Orb with styles | DONE (Part 10, v3.65) | dot styles in `Shapes`: earth, galaxy, atom, torus, geodesic, knot, dyson, face_male, face_female. v3.65: owner removed `dna`, `tesseract`, `wormhole` and the AGSL `sphere3d` (`fx/Sphere3D` deleted); a saved removed style falls back to earth (`Shapes.valid`). Faces = `FaceHD` on the MediaPipe canonical face mesh (`FaceMeshData`, Apache-2.0), 3200 dots with per-dot lighting (`Cloud.lum`); other styles draw 1400 dots and pad the rest as hidden. System animations off → frozen edge glow (`fx/Motion`) |
| Splash | EXISTS-OK | `DKSplash` (once per process, skipped when animations are off) — unchanged |
| Listening edge glow + overlay | DONE (Part 10) | `fx/EdgeGlowView` (~26dp, violet listening/thinking, pink speaking, red reserved for errors) added by the accessibility service as a non-touchable `TYPE_ACCESSIBILITY_OVERLAY` — covers DK's own screens and other apps, no extra overlay permission. Toggle: Settings → ओर्ब → किनारों की चमक (default on). Rules in `EdgeGlowRules` |
| Home, Chat, Voice, Memory/History, Settings, Debug | EXISTS-OK | View-based |
| Onboarding pager, Personality screen, Orb Style carousel, Permissions screen, Profile, Privacy, About | EXISTS-PARTIAL / MISSING | permissions + profile are Settings sections; no onboarding/about screen |
| Onboarding (4 pages) | DONE (Part 11) | `onboard/OnboardingActivity` — who DK is, "try saying" cards, mic + Accessibility buttons with live ✓/✗, your name. Only on a fresh install without a saved name; existing users are marked done silently. Skip on every page; "View intro again" in About |
| About + Privacy | DONE (Part 11) | Settings → ऐप के बारे में: name, tagline, version, `AssistantBrand.CREATED_BY` (धीरज कुमार सिंह), privacy facts that match the code (keys only on phone, `allowBackup=false`, screenshots only when screen viewing is on, notifications in memory, location not saved, camera = torch, social posts need "yes") |
| Permissions screen | EXISTS-OK | Settings → परमिशन (live status) + Settings → Accessibility — not duplicated |
| Home greeting by time | DONE (Part 11) | `Greeting` — सुप्रभात / नमस्ते / शुभ संध्या + user name |

## Part 12 — Instagram / Facebook / WhatsApp agent
| Item | Status | Notes |
|---|---|---|
| General screen agent that can drive any app | EXISTS-OK | `AgentRunner` (vision + accessibility), asks before risky actions |
| Agent core (`agent/core`): ScreenDriver, ScreenObservation, TargetResolver, BlockerDetector, UiActionExecutor, TaskStateMachine, ConfirmGate | BUILT | pure Kotlin, tested with `FakeScreenDriver` |
| AccessibilityScreenDriver | BUILT | top app window only; stale observation = no tap; accessibility click, then gesture on element bounds |
| Social tasks (`agent/social`): request parser, planner, runner, selectors (English + Hindi UI) | BUILT | Instagram post photo/video/reel/story, Facebook text/photo/video/story, WhatsApp send/read chat/search/mute/unmute/mark read |
| Live tools `social_media_task`, `whatsapp_task`, `social_media_task_control` | BUILT | WhatsApp read_unread reuses the existing notifications tool (no second copy) |
| Safety | BUILT | posting/sending needs the user's own "yes" after the question (checked against the real transcript); draft mode never posts; login/CAPTCHA/2FA/permission = DK waits for the user; unclear result = `submitted_unverified` |
| Media | BUILT | only files the user shared to DK (Gallery → Share → "DK को दें"), copied to app storage |
| On-device check against real Instagram/Facebook/WhatsApp screens | NOT VERIFIED | their layouts change often; selectors may need tuning on the phone |
| WhatsApp unread via notification listener + reply | EXISTS-OK | `DKNotifListener`, `NotifStore`, `notification_reply` tool |

## Part 13 — Access-key licensing
| Item | Status | Notes |
|---|---|---|
| AccessKeyRules (pure) + LicenseInfo | BUILT | `license/AccessKeyRules.kt`, tested in `AccessKeyRulesTest` |
| AccessKeyManager | BUILT | private prefs `dk_access_key`, activate + device hash, liveCheck max every 3 h, fail open, admin block = off at once |
| Backend config | BUILT | git-ignored `assets/dk_license.json`; missing = "licensing not configured" (app works as before). Contract in `docs/LICENSING.md` |
| Enforcement | BUILT | `DKAccessibilityService.startLive` + `AgentRunner.execute` refuse with admin message; Home re-checks on resume and stops Live if blocked |
| UI | BUILT | Home chip (only when configured and not active), Settings → Profile → Access key |
| Real backend server | NOT BUILT | the owner must run one (no ids/URLs in the repo by rule) |

## Part 14 — Website Forge
| Item | Status | Notes |
|---|---|---|
| Gemini SSE client (`WebsiteForgeClient`) | BUILT | model from ListModels (plain flash first), `thinkingBudget 0` only on 2.5, cancel = HTTP cancel, key only in header, never logged |
| HTML clean / repair / safety net (`ForgeHtml`, `SseParser`) | BUILT | fence strip, truncated repair, one-time opacity safety net, Hindi error text (503 = "व्यस्त") |
| Stage detection (`ForgeStageDetector`) | BUILT | Structure → Layout → Styling → Content → Motion → Finish, section names for the HUD |
| Controller (`ForgeController`) | BUILT | one build at a time, edit/retry/cancel, saves to app files `Websites/`, "ready" notification when screen hidden, offline + missing-key checks |
| 3D scene (`assets/forge/forge.html`, `forge.js`, three.js r160 bundled, MIT licence included) | BUILT | glyph stream + section slabs + bloom, reveal with 2.6 s safety timeout; checked in headless Chromium |
| Low-RAM / no-WebGL fallback (`ForgeFallbackView`) | BUILT | plain Canvas progress view |
| Result viewer (`ForgeActivity`) | BUILT | sandboxed WebView (no file access, JS only), Save (system file picker), Share / Open in browser via FileProvider, Edit, My websites, delete with confirmation |
| Entry points | BUILT | DK Live `build_website`, chat/voice intent, Home chip "🌐 वेबसाइट" |
| Tests | BUILT | `forge/ForgeTest.kt` (SSE, HTML, stages, intent, model choice) |
| On-device check of WebGL performance | NOT VERIFIED | needs a real phone |

## Part 15 — Tests, debug, release
| Item | Status | Notes |
|---|---|---|
| JVM unit tests | EXISTS-OK | 47 test files, run on every PR |
| Debug screen | EXISTS-OK | Settings → debug log (`DKLog`, `SevenDebug`) |
| Release APK on GitHub Releases | EXISTS-OK | `build.yml` builds and publishes on every merge to `main` |
| minify/shrink, docs/RELEASE.md | DONE (Part 15) | `docs/RELEASE.md`; `app/proguard-rules.pro`; R8 + resource shrink opt-in with `DK_MINIFY=1` (default off until checked on a phone); local signing from git-ignored `keystore.properties` (CI env still wins) |

## v3.66 — Owner voice only (layer 3) + far-voice gate (layer 4)
| Item | Status | Notes |
|---|---|---|
| Owner voiceprint | DONE | `voice/OwnerVoice.kt` (ONNX Runtime 1.31, CAM++ zh_en, downloaded once with SHA-256 check), `voice/SpeakerFbank.kt` (Kaldi fbank, matches kaldi-native-fbank within 1.5e-4), `voice/VoicePrint.kt`, `voice/OwnerGate.kt`; Live + wake-word path; Settings enrolment (5 phrases) + test + delete |
| Zero-latency design | DONE | audio streams to Gemini immediately; first 1 s is scored in parallel; OTHER → rest of audio silenced, reply not played, tool calls answered "IGNORED" without running |
| Far / quiet voice gate | DONE | `TurnDetector.startFloor` from the owner's learned speech level (off on Bluetooth) |
| Faster end of turn | DONE | `TurnEnd.complete()` → 360 ms instead of 550 ms when the caption is a complete sentence |
| Barge-in while DK speaks | NOT GATED (v1) | interrupting DK still works for any voice |
| Offline evaluation | DONE (English only) | LibriSpeech dev-clean, 40 speakers, 5-phrase enrolment: EER 1.6% at 1 s audio, 0.6% full utterance; Kotlin pipeline = Python (cos 0.99999999) |
| On-phone accuracy / speed | NOT VERIFIED | needs a real phone (Hindi, noise, phone mic) |

## v3.67 — Fast, honest phone actions
| Item | Status | Notes |
|---|---|---|
| "यूट्यूब खोलो और उसपे पवन सिंह का सॉन्ग सर्च करके प्ले कर दो" | FIXED | was routed to *search* with query "उसपे पवन सिंह का सॉन्ग"; now `youtube_play` "पवन सिंह का सॉन्ग" (सॉन्ग/सांग words, उसपे/इसपर particles, "खोलो" stripped from the query) |
| YouTube first-video lookup | FASTER | `YtPage.firstVideoId` streams the results page and stops at the first `videoRenderer` (was: whole ~1.7 MB page) |
| No "हो गया" before the work | DONE | `DoneGate` + `GeminiLive`: for an action request Gemini's audio is held until a tool runs / the phone's own fast action succeeds; a success claim before that (or after a tool that only *started* / failed) is not played and Gemini is told to do the task / say the real state |
| WhatsApp send without asking | DONE (opt-in, default OFF) | Settings → "WhatsApp संदेश"; still checks the message reached the chat before "भेज दिया" |
| Delete last photo(s)/screenshot(s) | DONE (Android 11+) | `MediaDelete` + `DeleteMediaActivity`: Android's own Allow box, moved to trash (30-day undo), re-checked; only via Gemini `phone_task`, never from the instant path; Android 10 and older: honest "not possible" |
| Instagram DM | NOT BUILT | needs testing on a real phone; Instagram posting stays as before |
| On-phone timing | NOT VERIFIED | log line "तुरंत काम … काम+जाँच X ms" shows the real time |

## v3.68 — Writing and speaking split (voice engines)
| Item | Status | Notes |
|---|---|---|
| Split | DONE | `GeminiLive` asks Live for `responseModalities: TEXT`; the text streams into `voice/engine/SpeechPipeline`. Native-audio Live models accept only AUDIO - if TEXT setup is refused, that model is remembered and the split runs on AUDIO + output transcription (Live's audio is not played) |
| Three engines, one interface | DONE | `VoiceEngine` (`speak/stop/preview`); `GeminiTtsEngine` (default ON, via `worker/dk-brain`, key only in the Worker), `EdgeTtsEngine` (OFF, Swara/Madhur, rate/pitch sliders, no emotion), `LiveNativeEngine` (OFF, old behaviour). Engines are found by class name - deleting a file removes the engine |
| Pipeline | DONE | `TextChunker` (first piece at a sentence end or a comma after 5 words, forced at 8; later 6-28 words), 2 prefetch threads, one AudioTrack queue, `Joint` silence trim + 5 ms fade-in + fixed pause per punctuation, warm Worker/Edge sockets, stalled first piece flushed after 250 ms |
| Settings | DONE | Settings -> आवाज़ -> "बोलने वाला इंजन (DK लाइव)": 3 switches (one at a time), voice list with ▶ सुनो per voice, Gemini style box, editable sample sentence, "तीनों बारी-बारी सुनो", last measured speech-end -> first-audio per engine |
| Fallback | DONE | per piece: chosen engine -> Gemini TTS -> phone TTS; failed engine skipped for 120 s; no ready engine -> Live's own voice |
| Lip-sync | DONE | `VoiceLevel` schedules the real RMS of whatever is playing (any engine); `OrbView` uses it while speaking |
| Barge-in | DONE | `flushPlayback` also stops the pipeline queue and the playing piece |
| On-phone timing | NOT VERIFIED | log "बोलना ख़त्म → पहली आवाज़ (…): X ms" + settings line. Sandbox: Edge connect 82-113 ms, first audio of a 5-8 word piece 37-125 ms on a warm socket. Gemini TTS/Live need the key (not measured here) |

## Deviations decided for this repo
1. Package stays `com.dk.assistant` (existing installs must keep updating).
2. Existing View screens are kept (Step 5). No Compose rewrite of working screens.
3. Default reply language stays **Hinglish** for existing users; **Auto** is a new choice in Settings.
4. A personality change applies to DK Live on its next connection (same as the existing language setting).
