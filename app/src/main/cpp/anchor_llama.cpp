#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
std::atomic<bool> cancelled{false};
std::once_flag initialized;
struct Engine {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
std::mutex enginesMutex;
std::map<jlong, std::shared_ptr<Engine>> engines;
jlong nextHandle = 1;
bool abortGeneration(void *) { return cancelled.load(std::memory_order_relaxed); }
bool loadProgress(float, void *) { return !cancelled.load(std::memory_order_relaxed); }
double millis(Clock::time_point start, Clock::time_point end) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}
void error(JNIEnv *env, const std::string &message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}
std::string utf8(JNIEnv *env, jstring value) {
    if (!value) return "";
    // Java's modified UTF-8 is unsuitable for emoji. Convert through UTF-8 bytes.
    jclass cls = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, charset));
    std::string result;
    if (bytes) {
        auto length = env->GetArrayLength(bytes);
        result.resize(length);
        if (length) env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(&result[0]));
        env->DeleteLocalRef(bytes);
    }
    env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return result;
}
jbyteArray bytes(JNIEnv *env, const std::string &value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result && !value.empty()) env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    return result;
}
void trimIncompleteUtf8(std::string &text) {
    if (text.empty()) return;
    size_t first = text.size() - 1;
    while (first > 0 && (static_cast<unsigned char>(text[first]) & 0xC0) == 0x80) --first;
    unsigned char lead = text[first];
    size_t required = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    if (text.size() - first < required) text.resize(first);
}
std::shared_ptr<Engine> engine(jlong id) {
    std::lock_guard<std::mutex> lock(enginesMutex);
    auto found = engines.find(id);
    if (found == engines.end()) throw std::runtime_error("The model has been unloaded.");
    return found->second;
}
struct Message { std::string role, content; };
// BEGIN LFM3 PLAIN TEXT FORMATTER
// Matches LiquidAI/LFM3-1.7B's plain-text Jinja subset with enable_thinking=false.
// This app supplies only system/user/assistant strings, never tool or media data.
std::string stripLfm3ThinkingWhitespace(const std::string &text) {
    // Python/Jinja str.strip whitespace, encoded as UTF-8, independent of locale.
    static const std::vector<std::string> spaces = {
        "\t", "\n", "\v", "\f", "\r", " ", "\x1c", "\x1d", "\x1e", "\x1f",
        "\xc2\x85", "\xc2\xa0", "\xe1\x9a\x80", "\xe2\x80\x80", "\xe2\x80\x81",
        "\xe2\x80\x82", "\xe2\x80\x83", "\xe2\x80\x84", "\xe2\x80\x85", "\xe2\x80\x86",
        "\xe2\x80\x87", "\xe2\x80\x88", "\xe2\x80\x89", "\xe2\x80\x8a", "\xe2\x80\xa8",
        "\xe2\x80\xa9", "\xe2\x80\xaf", "\xe2\x81\x9f", "\xe3\x80\x80"
    };
    size_t first = 0, last = text.size();
    bool changed = true;
    while (changed) {
        changed = false;
        for (const auto &space : spaces) {
            if (space.size() <= last - first && text.compare(first, space.size(), space) == 0) {
                first += space.size(); changed = true; break;
            }
        }
    }
    changed = true;
    while (changed) {
        changed = false;
        for (const auto &space : spaces) {
            if (space.size() <= last - first && text.compare(last - space.size(), space.size(), space) == 0) {
                last -= space.size(); changed = true; break;
            }
        }
    }
    return text.substr(first, last - first);
}
std::string formatLfm3PlainText(const std::vector<Message> &messages, const std::string &bos) {
    if (messages.empty() || messages.back().role != "user")
        throw std::runtime_error("LFM3 plain-text chat requires a current user message.");
    std::string result = bos;
    for (size_t i = 0; i < messages.size(); ++i) {
        const auto &message = messages[i];
        if (message.role != "user" && message.role != "assistant" && !(i == 0 && message.role == "system"))
            throw std::runtime_error("This LFM3 adapter supports only plain-text system, user and assistant messages.");
        if (i == 0 && message.role == "system" && message.content.empty()) continue;
        result += "<|im_start|>" + message.role + "\n";
        if (message.role == "assistant") {
            // All completed assistant turns precede the current user request.
            const size_t closing = message.content.rfind("</think>");
            const std::string visible = closing == std::string::npos ? message.content
                : stripLfm3ThinkingWhitespace(message.content.substr(closing + 8));
            result += "<think></think>" + visible;
        } else result += message.content;
        result += "<|im_end|>\n";
    }
    result += "<|im_start|>assistant\n<think></think>";
    return result;
}
// END LFM3 PLAIN TEXT FORMATTER
std::string format(const llama_model *model, const std::vector<Message> &messages) {
    std::vector<llama_chat_message> chat;
    size_t size = 256;
    for (const auto &m : messages) { chat.push_back({m.role.c_str(), m.content.c_str()}); size += m.content.size() + 64; }
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) throw std::runtime_error("This GGUF has no chat template. Import an instruct/chat model with its template.");
    char architecture[64] = {};
    const int architectureLength = llama_model_meta_val_str(model, "general.architecture", architecture, sizeof(architecture));
    if (architectureLength == 4 && std::string(architecture) == "lfm3") {
        const std::string modelTemplate(tmpl);
        if (modelTemplate.find("enable_thinking") == std::string::npos || modelTemplate.find("<think></think>") == std::string::npos)
            throw std::runtime_error("This LFM3 chat template is unsupported. Use the documented LFM3-1.7B instruct GGUF.");
        const auto *vocab = llama_model_get_vocab(model);
        std::string bos;
        // The official LFM3 GGUF disables automatic BOS, although its template
        // explicitly emits it. Avoid duplication for a file that enables it.
        if (!llama_vocab_get_add_bos(vocab)) {
            const llama_token bosToken = llama_vocab_bos(vocab);
            if (bosToken == LLAMA_TOKEN_NULL) throw std::runtime_error("This LFM3 GGUF has no beginning-of-text token.");
            const char *bosText = llama_vocab_get_text(vocab, bosToken);
            if (!bosText || !*bosText) throw std::runtime_error("This LFM3 GGUF has an invalid beginning-of-text token.");
            bos = bosText;
        }
        return formatLfm3PlainText(messages, bos);
    }
    std::vector<char> buffer(size * 2);
    int length = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buffer.data(), buffer.size());
    if (length < 0) throw std::runtime_error("This model's chat template is unsupported by this runtime. Use the documented Granite or Qwen model.");
    if (length >= static_cast<int>(buffer.size())) {
        buffer.resize(length + 1);
        length = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buffer.data(), buffer.size());
    }
    if (length < 0 || length >= static_cast<int>(buffer.size())) throw std::runtime_error("Could not format the model prompt.");
    return std::string(buffer.data(), length);
}
std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &prompt) {
    int length = llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (length == INT32_MIN) throw std::runtime_error("Prompt is too long.");
    std::vector<llama_token> tokens(std::max(1, -length));
    length = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
    if (length < 0) throw std::runtime_error("Could not tokenize the prompt.");
    tokens.resize(length); return tokens;
}
}

extern "C" JNIEXPORT void JNICALL Java_dev_anchor_app_NativeLlama_nativeCancel(JNIEnv *, jclass) { cancelled = true; }
extern "C" JNIEXPORT void JNICALL Java_dev_anchor_app_NativeLlama_nativeResetCancellation(JNIEnv *, jclass) { cancelled = false; }
extern "C" JNIEXPORT jstring JNICALL Java_dev_anchor_app_NativeLlama_nativeSystemInfo(JNIEnv *env, jclass) { return env->NewStringUTF(llama_print_system_info()); }
extern "C" JNIEXPORT jlong JNICALL Java_dev_anchor_app_NativeLlama_nativeLoad(JNIEnv *env, jclass, jstring path, jint context, jint threads) {
    try {
        std::call_once(initialized, [] { ggml_backend_load_all(); llama_backend_init(); });
        auto instance = std::make_shared<Engine>();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0; mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        mp.progress_callback = loadProgress;
        instance->model = llama_model_load_from_file(utf8(env, path).c_str(), mp);
        if (!instance->model) throw std::runtime_error(cancelled ? "Model loading stopped." : "Could not load this GGUF. It may be unsupported, damaged, or too large for available RAM.");
        if (cancelled) throw std::runtime_error("Model loading stopped.");
        auto cp = llama_context_default_params();
        cp.n_ctx = std::clamp(static_cast<int>(context), 512, 8192);
        cp.n_batch = 512; cp.n_ubatch = 256;
        cp.n_threads = std::clamp(static_cast<int>(threads), 1, 4); cp.n_threads_batch = cp.n_threads;
        cp.abort_callback = abortGeneration; cp.no_perf = false;
        instance->context = llama_init_from_model(instance->model, cp);
        if (!instance->context) throw std::runtime_error("Could not allocate the model context. Try a smaller model after closing other apps.");
        std::lock_guard<std::mutex> lock(enginesMutex);
        jlong id = nextHandle++; engines[id] = instance; return id;
    } catch (const std::exception &e) { error(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_dev_anchor_app_NativeLlama_nativeFree(JNIEnv *, jclass, jlong id) {
    std::shared_ptr<Engine> removed;
    { std::lock_guard<std::mutex> lock(enginesMutex); auto found = engines.find(id); if (found != engines.end()) { removed = found->second; engines.erase(found); } }
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_anchor_app_NativeLlama_nativeDescription(JNIEnv *env, jclass, jlong id) {
    try { auto e = engine(id); char desc[256]; llama_model_desc(e->model, desc, sizeof(desc)); return env->NewStringUTF(desc); }
    catch (const std::exception &e) { error(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT jobject JNICALL Java_dev_anchor_app_NativeLlama_nativeGenerate(JNIEnv *env, jclass, jlong id, jobjectArray roles, jobjectArray contents, jint maxTokens, jobject listener) {
    try {
        auto e = engine(id);
        const auto *vocab = llama_model_get_vocab(e->model);
        auto started = Clock::now();
        std::vector<Message> messages;
        auto count = env->GetArrayLength(roles);
        if (count < 1 || count != env->GetArrayLength(contents)) throw std::runtime_error("Invalid chat request.");
        for (int i = 0; i < count; ++i) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
            messages.push_back({utf8(env, role), utf8(env, content)});
            env->DeleteLocalRef(role); env->DeleteLocalRef(content);
        }
        // A bounded history may start mid-turn. Preserve valid role alternation.
        size_t firstTurn = messages[0].role == "system" ? 1 : 0;
        while (firstTurn + 1 < messages.size() && messages[firstTurn].role == "assistant")
            messages.erase(messages.begin() + firstTurn);
        for (size_t i = firstTurn + 1; i < messages.size();) {
            if (messages[i].role == messages[i - 1].role) {
                messages[i - 1].content += "\n" + messages[i].content;
                messages.erase(messages.begin() + i);
            } else ++i;
        }
        const int limit = std::clamp(static_cast<int>(maxTokens), 1, 2048);
        const int budget = static_cast<int>(llama_n_ctx(e->context)) - limit - 8;
        std::vector<llama_token> tokens;
        while (true) {
            tokens = tokenize(vocab, format(e->model, messages));
            if (static_cast<int>(tokens.size()) <= budget) break;
            // Preserve system policy and the current user request, discard oldest turns.
            size_t removable = messages[0].role == "system" ? 1 : 0;
            if (removable + 1 >= messages.size()) throw std::runtime_error("This message and memory exceed the phone context. Shorten the message or remove memory notes.");
            messages.erase(messages.begin() + removable);
            while (removable + 1 < messages.size() && messages[removable].role == "assistant")
                messages.erase(messages.begin() + removable);
        }
        llama_memory_clear(llama_get_memory(e->context), true);
        llama_perf_context_reset(e->context);
        for (size_t offset = 0; offset < tokens.size() && !cancelled; offset += 128) {
            int n = std::min<size_t>(128, tokens.size() - offset);
            auto batch = llama_batch_get_one(tokens.data() + offset, n);
            if (llama_decode(e->context, batch) != 0 && !cancelled) throw std::runtime_error("Prompt processing failed. Available memory may be too low.");
        }
        auto sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.6f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
        std::string output;
        int generated = 0;
        Clock::time_point first;
        jclass callbackClass = listener ? env->GetObjectClass(listener) : nullptr;
        jmethodID callback = callbackClass ? env->GetMethodID(callbackClass, "onToken", "([BI)V") : nullptr;
        for (; generated < limit && !cancelled;) {
            llama_token token = llama_sampler_sample(sampler.get(), e->context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            if (generated == 0) first = Clock::now();
            ++generated;
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (size < 0) { piece.resize(-size); size = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (size > 0) output.append(piece.data(), size);
            if (callback) {
                auto text = bytes(env, output);
                env->CallVoidMethod(listener, callback, text, generated); env->DeleteLocalRef(text);
                if (env->ExceptionCheck()) return nullptr;
            }
            if (generated < limit && !cancelled) {
                auto batch = llama_batch_get_one(&token, 1);
                if (llama_decode(e->context, batch) != 0 && !cancelled) throw std::runtime_error("Generation failed. Try a smaller model.");
            }
        }
        auto finished = Clock::now();
        trimIncompleteUtf8(output);
        if (callbackClass) env->DeleteLocalRef(callbackClass);
        jclass cls = env->FindClass("dev/anchor/app/NativeLlama$Result");
        jmethodID ctor = env->GetMethodID(cls, "<init>", "([BIIDDZ)V");
        auto text = bytes(env, output);
        jobject result = env->NewObject(cls, ctor, text, static_cast<jint>(tokens.size()), generated,
            generated ? millis(started, first) : -1.0, generated ? millis(first, finished) : 0.0, static_cast<jboolean>(cancelled.load()));
        env->DeleteLocalRef(text); env->DeleteLocalRef(cls); return result;
    } catch (const std::exception &e) { error(env, e.what()); return nullptr; }
}
