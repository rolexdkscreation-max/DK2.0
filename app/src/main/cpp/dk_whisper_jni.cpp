// 18 नवंबर 2026: DK का Whisper पुल (JNI) - Kotlin: com.dk.assistant.stt.WhisperNative
// सिर्फ़ चार काम: मॉडल खोलो, आवाज़ (16 kHz float) से text, बीच में रोको, मॉडल बंद.
// कोई exception C++ से बाहर नहीं जाता - गलती = null / 0 (Kotlin साफ़ हिंदी संदेश दिखाता है).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include "whisper.h"

#define TAG "DKWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

static bool dk_abort_cb(void * /*user*/) { return g_abort.load(); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_dk_assistant_stt_WhisperNative_initContext(JNIEnv *env, jclass, jstring jpath) {
    if (jpath == nullptr) return 0;
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = nullptr;
    try { ctx = whisper_init_from_file_with_params(path, cp); } catch (...) { ctx = nullptr; }
    env->ReleaseStringUTFChars(jpath, path);
    LOGI("init: %s", ctx ? "ok" : "failed");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_dk_assistant_stt_WhisperNative_freeContext(JNIEnv *, jclass, jlong ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_dk_assistant_stt_WhisperNative_setAbort(JNIEnv *, jclass, jboolean on) {
    g_abort.store(on == JNI_TRUE);
}

JNIEXPORT jstring JNICALL
Java_com_dk_assistant_stt_WhisperNative_systemInfo(JNIEnv *env, jclass) {
    const char *s = whisper_print_system_info();
    return env->NewStringUTF(s ? s : "");
}

/**
 * samples = 16 kHz mono float (-1..1). lang = "hi". prompt = शुरुआती संकेत (देवनागरी में लिखो).
 * audioCtx = 0 -> पूरा (1500); छोटी बात पर कम = कई गुना तेज़. लौटाता: UTF-8 bytes, null = गलती / रोका गया.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_dk_assistant_stt_WhisperNative_transcribe(JNIEnv *env, jclass, jlong ptr, jfloatArray jsamples,
                                                   jstring jlang, jint threads, jstring jprompt, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr || jsamples == nullptr) return nullptr;
    const jsize n = env->GetArrayLength(jsamples);
    if (n <= 0) return nullptr;
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);
    if (samples == nullptr) return nullptr;
    const char *lang = jlang ? env->GetStringUTFChars(jlang, nullptr) : nullptr;
    const char *prompt = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = false;
    p.language = (lang && lang[0]) ? lang : "hi";
    p.detect_language = false;
    p.n_threads = threads > 0 ? threads : 4;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = false;
    p.suppress_blank = true;
    p.suppress_nst = true;
    p.temperature = 0.0f;
    p.temperature_inc = 0.0f;     // दोबारा-दोबारा decode नहीं (देर) - छोटी बातों पर greedy काफ़ी
    p.greedy.best_of = 1;
    p.initial_prompt = (prompt && prompt[0]) ? prompt : nullptr;
    if (audioCtx > 0) p.audio_ctx = audioCtx;
    g_abort.store(false);
    p.abort_callback = dk_abort_cb;
    p.abort_callback_user_data = nullptr;

    int rc = -1;
    try { rc = whisper_full(ctx, p, samples, n); } catch (...) { rc = -1; }
    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);

    // UTF-8 bytes लौटाओ (NewStringUTF अधूरे / 4-byte अक्षर पर JNI को गिरा सकता है) - Kotlin decode करता है
    jbyteArray out = nullptr;
    if (rc == 0 && !g_abort.load()) {
        std::string text;
        const int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; ++i) {
            const char *t = whisper_full_get_segment_text(ctx, i);
            if (t) text += t;
        }
        out = env->NewByteArray((jsize) text.size());
        if (out != nullptr && !text.empty())
            env->SetByteArrayRegion(out, 0, (jsize) text.size(), reinterpret_cast<const jbyte *>(text.data()));
    }
    if (lang) env->ReleaseStringUTFChars(jlang, lang);
    if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);
    return out;
}

} // extern "C"
