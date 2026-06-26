#include <chrono>
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "ggml/LanguageModel.h"

namespace {

using Clock = std::chrono::steady_clock;

long long elapsed_ms(const Clock::time_point start, const Clock::time_point end) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
}

std::string repeat_to_length(const std::string &seed, const size_t target) {
    std::string out;
    while (out.size() < target) {
        if (!out.empty()) {
            out.push_back(' ');
        }
        out += seed;
    }
    out.resize(target);
    return out;
}

std::vector<int> make_prompt(LanguageModel &model, const std::string &text) {
    auto tokens = model.tokenize(text + " ");
    tokens.insert(tokens.begin(), 1); // Existing JNI path prepends BOS before model eval.
    return tokens;
}

void run_case(LanguageModel &model, const std::string &name, const std::string &text) {
    const auto tokenize_start = Clock::now();
    auto tokens = make_prompt(model, text);
    const auto tokenize_end = Clock::now();

    if (tokens.size() >= LLAMA_CONTEXT_SIZE) {
        std::cout << name << ",skipped,tokens=" << tokens.size()
                  << ",reason=context_window" << std::endl;
        return;
    }

    model.transformerContext.active_context.clear();

    const auto cold_start = Clock::now();
    model.updateContext(tokens);
    model.infer();
    const auto cold_end = Clock::now();

    const auto repeat_start = Clock::now();
    model.updateContext(tokens);
    model.infer();
    const auto repeat_end = Clock::now();

    auto appended = make_prompt(model, text + " next");
    const auto append_start = Clock::now();
    model.updateContext(appended);
    model.infer();
    const auto append_end = Clock::now();

    std::cout << name
              << ",chars=" << text.size()
              << ",tokens=" << tokens.size()
              << ",tokenize_ms=" << elapsed_ms(tokenize_start, tokenize_end)
              << ",cold_eval_ms=" << elapsed_ms(cold_start, cold_end)
              << ",cached_repeat_ms=" << elapsed_ms(repeat_start, repeat_end)
              << ",append_ms=" << elapsed_ms(append_start, append_end)
              << std::endl;
}

std::string printable_token(const std::string &token) {
    std::string out;
    for (const char c : token) {
        if (c == '\n') {
            out += "\\n";
        } else if (c == '\t') {
            out += "\\t";
        } else if (c == '\r') {
            out += "\\r";
        } else {
            out.push_back(c);
        }
    }
    return out;
}

void run_prediction_case(LanguageModel &model, const std::string &name, const std::string &text) {
    auto tokens = make_prompt(model, text);
    if (tokens.size() >= LLAMA_CONTEXT_SIZE) {
        std::cout << "\n[" << name << "] skipped: context_window tokens=" << tokens.size() << std::endl;
        return;
    }

    model.transformerContext.active_context.clear();

    const auto start = Clock::now();
    model.updateContext(tokens);
    auto logits = model.infer();
    const auto end = Clock::now();

    std::vector<int> ids;
    ids.reserve(logits.size());
    for (size_t i = 0; i < logits.size(); ++i) {
        const std::string decoded = model.decode({static_cast<int>(i)});
        if (decoded.empty() || decoded[0] == '<') {
            continue;
        }
        ids.push_back(static_cast<int>(i));
    }

    constexpr int top_n = 12;
    std::partial_sort(ids.begin(), ids.begin() + top_n, ids.end(),
                      [&logits](const int a, const int b) {
                          return logits[a] > logits[b];
                      });

    const float max_logit = logits[ids[0]];
    float denom = 0.0f;
    for (int i = 0; i < top_n; ++i) {
        denom += std::exp(logits[ids[i]] - max_logit);
    }

    std::cout << "\n[" << name << "]"
              << " chars=" << text.size()
              << " tokens=" << tokens.size()
              << " eval_ms=" << elapsed_ms(start, end)
              << "\ncontext: " << text
              << "\ntop_next_tokens:" << std::endl;

    for (int i = 0; i < top_n; ++i) {
        const int id = ids[i];
        const float approx_prob = std::exp(logits[id] - max_logit) / denom;
        std::cout << "  " << std::setw(2) << (i + 1)
                  << ". id=" << std::setw(5) << id
                  << " p_top12=" << std::fixed << std::setprecision(3) << approx_prob
                  << " token=\"" << printable_token(model.decode({id})) << "\""
                  << std::endl;
    }
}

} // namespace

int main(int argc, char **argv) {
    if (argc != 2) {
        std::cerr << "usage: " << argv[0] << " /path/to/ml4_q6_k.gguf" << std::endl;
        return 2;
    }

    const std::string model_path = argv[1];
    const auto load_start = Clock::now();
    std::unique_ptr<LanguageModel> model(LlamaAdapter::createLanguageModel(model_path));
    const auto load_end = Clock::now();

    if (!model) {
        std::cerr << "failed to load model: " << model_path << std::endl;
        return 1;
    }

    std::cout << "model_load_ms=" << elapsed_ms(load_start, load_end)
              << ",vocab=" << model->getVocabSize() << std::endl;

    const std::string seed =
            "I am writing a message about syncing clipboard text and screenshots between "
            "Android and Windows, and I want the keyboard to predict the next word from "
            "nearby sentence context.";

    run_case(*model, "short_current_sentence", "I am typing a quick message about clipboard sync");
    run_case(*model, "context_256_chars", repeat_to_length(seed, 256));
    run_case(*model, "context_512_chars", repeat_to_length(seed, 512));
    run_case(*model, "context_1024_chars", repeat_to_length(seed, 1024));
    run_case(*model, "context_1536_chars", repeat_to_length(seed, 1536));

    run_prediction_case(*model, "chat_no_context",
                        "Can you send me the");
    run_prediction_case(*model, "chat_meeting_context",
                        "We moved the project meeting to Friday afternoon. Can you send me the");
    run_prediction_case(*model, "clipboard_sync_context",
                        "The Android keyboard syncs copied screenshots and clipboard text to Windows. Can you send me the");
    run_prediction_case(*model, "email_field_context",
                        "Please email the invoice and receipt to");
    run_prediction_case(*model, "code_field_context",
                        "The Kotlin function should return");
    run_prediction_case(*model, "search_field_context",
                        "best way to fix android keyboard");
    run_prediction_case(*model, "literal_metadata_whatsapp",
                        "[app=com.whatsapp field=Message] Can you send me the");
    run_prediction_case(*model, "natural_metadata_whatsapp",
                        "In a WhatsApp message, Can you send me the");
    run_prediction_case(*model, "literal_metadata_browser",
                        "[app=com.android.chrome field=Search] best way to fix android keyboard");
    run_prediction_case(*model, "natural_metadata_browser",
                        "In a browser search field, best way to fix android keyboard");
    run_prediction_case(*model, "when_plain",
                        "When");
    run_prediction_case(*model, "when_can_you",
                        "When can you");
    run_prediction_case(*model, "when_meeting_context",
                        "We moved the project meeting to Friday afternoon. When");
    run_prediction_case(*model, "when_sync_context",
                        "The Android keyboard syncs copied screenshots and clipboard text to Windows. When");
    run_prediction_case(*model, "work_deadline",
                        "The client asked for the revised proposal before the end of the day. I will send the");
    run_prediction_case(*model, "work_followup",
                        "Thanks for joining the call earlier. The main action item is to");
    run_prediction_case(*model, "casual_dinner",
                        "I'm leaving work now and should be home around seven. Do you want to get");
    run_prediction_case(*model, "casual_late",
                        "Sorry I'm running late, traffic is worse than expected. I'll be there in");
    run_prediction_case(*model, "screenshot_share",
                        "I just took a screenshot of the error on my phone. Can you check the");
    run_prediction_case(*model, "clipboard_windows",
                        "The text copied on Windows did not show up on Android, so I need to check the");
    run_prediction_case(*model, "cloudflare_worker",
                        "The Cloudflare Worker receives clipboard events and forwards them to paired devices. It should ignore");
    run_prediction_case(*model, "r2_file_link",
                        "When I share a file, upload it to R2 and copy a temporary download");
    run_prediction_case(*model, "android_setting",
                        "In the keyboard settings screen, add a toggle to enable");
    run_prediction_case(*model, "kotlin_bug",
                        "The Kotlin coroutine should not run on the main thread because it can");
    run_prediction_case(*model, "code_exception",
                        "If the upload fails with signature mismatch, the client should");
    run_prediction_case(*model, "email_invoice",
                        "Hi Sarah, attached is the invoice for last month. Please let me know if");
    run_prediction_case(*model, "address_field",
                        "My shipping address is 123 Main");
    run_prediction_case(*model, "name_field",
                        "My name is");
    run_prediction_case(*model, "search_weather",
                        "weather tomorrow in");
    run_prediction_case(*model, "search_android",
                        "samsung automatically copy screenshot to");
    run_prediction_case(*model, "question_fix",
                        "Why does the keyboard crash when I");
    run_prediction_case(*model, "autocorrect_context",
                        "I really need better context aware auto correct and");
    run_prediction_case(*model, "history_context",
                        "The keyboard should learn from things I typed before so it can suggest");
    run_prediction_case(*model, "literal_app_gmail",
                        "[app=com.google.android.gm field=Compose] Hi Sarah, attached is the");
    run_prediction_case(*model, "natural_app_gmail",
                        "In a Gmail compose field, Hi Sarah, attached is the");
    run_prediction_case(*model, "literal_app_vscode",
                        "[app=com.termux field=Terminal] git status");
    run_prediction_case(*model, "natural_app_terminal",
                        "In a terminal command line, git status");

    return 0;
}
