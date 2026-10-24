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
| `build_website` tool | MISSING | Part 14 |

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
| Forge intent from chat | MISSING | Part 14 |

## Part 09–11b — Design system, 3D orb, screens
| Item | Status | Notes |
|---|---|---|
| Theme / glass design | EXISTS-PARTIAL | own "liquid glass" design (`Glass`, `LiquidGlass`, `Shapes`), not the "pearl of light" tokens |
| Orb with styles | EXISTS-PARTIAL | `OrbView` Canvas (earth, galaxy, atom, dna, torus, faces); no AGSL shader |
| Splash | EXISTS-OK | `DKSplash` |
| Listening edge glow + overlay | MISSING | |
| Home, Chat, Voice, Memory/History, Settings, Debug | EXISTS-OK | View-based |
| Onboarding pager, Personality screen, Orb Style carousel, Permissions screen, Profile, Privacy, About | EXISTS-PARTIAL / MISSING | permissions + profile are Settings sections; no onboarding/about screen |

## Part 12 — Instagram / Facebook / WhatsApp agent
| Item | Status | Notes |
|---|---|---|
| General screen agent that can drive any app | EXISTS-OK | `AgentRunner` (vision + accessibility), asks before risky actions |
| ScreenDriver / TaskStateMachine / social task tools | MISSING | |
| WhatsApp unread via notification listener + reply | EXISTS-OK | `DKNotifListener`, `NotifStore`, `notification_reply` tool |

## Part 13 — Access-key licensing
| Item | Status | Notes |
|---|---|---|
| AccessKeyRules / AccessKeyManager / enforcement / UI | MISSING | needs a backend that the owner controls |

## Part 14 — Website Forge
| Item | Status | Notes |
|---|---|---|
| Whole feature (client, controller, 3D scene, HUD, result viewer) | MISSING | |

## Part 15 — Tests, debug, release
| Item | Status | Notes |
|---|---|---|
| JVM unit tests | EXISTS-OK | 47 test files, run on every PR |
| Debug screen | EXISTS-OK | Settings → debug log (`DKLog`, `SevenDebug`) |
| Release APK on GitHub Releases | EXISTS-OK | `build.yml` builds and publishes on every merge to `main` |
| minify/shrink, docs/RELEASE.md | MISSING | |

## Deviations decided for this repo
1. Package stays `com.dk.assistant` (existing installs must keep updating).
2. Existing View screens are kept (Step 5). No Compose rewrite of working screens.
3. Default reply language stays **Hinglish** for existing users; **Auto** is a new choice in Settings.
4. A personality change applies to DK Live on its next connection (same as the existing language setting).
