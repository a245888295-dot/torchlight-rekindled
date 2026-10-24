#include "render/text_layout.h"

#include <OgreFont.h>

namespace tl::render {

namespace {

/// The code point that starts at `i`, as UTF-8, and `i` moved past it. A byte
/// that is not a lead byte stands for itself, so Latin text measures as it
/// always did and a Chinese character is one glyph rather than three.
Ogre::Font::CodePoint next_code_point(const std::string& text, std::size_t& i) {
    const auto lead = static_cast<unsigned char>(text[i]);
    std::size_t extra = 0;
    if (lead >= 0xF0) extra = 3;
    else if (lead >= 0xE0) extra = 2;
    else if (lead >= 0xC0) extra = 1;
    Ogre::Font::CodePoint code = extra == 0 ? lead : (lead & (0xFFu >> (extra + 1)));
    ++i;
    for (std::size_t k = 0; k < extra && i < text.size(); ++k, ++i) {
        code = (code << 6) | (static_cast<unsigned char>(text[i]) & 0x3Fu);
    }
    return code;
}

}  // namespace



std::vector<std::string> wrap_text(const Ogre::FontPtr& font, const std::string& text, float width,
                                   float char_height, float across, float down) {
    std::vector<std::string> lines;
    if (!font) {
        lines.push_back(text);
        return lines;
    }

    const float coefficient = across > 0.0f ? down / across : 1.0f;
    auto measure = [&](const std::string& s) {
        float total = 0.0f;
        for (std::size_t i = 0; i < s.size();) {
            total += font->getGlyphInfo(next_code_point(s, i)).advance * char_height * coefficient;
        }
        return total;
    };

    std::string flattened;
    flattened.reserve(text.size());
    for (size_t i = 0; i < text.size(); ++i) {
        if (text[i] == '\\' && i + 1 < text.size() && text[i + 1] == 'n') {
            flattened.push_back('\n');
            ++i;
            continue;
        }
        flattened.push_back(text[i]);
    }

    std::string current;
    std::string word;
    auto flush_word = [&]() {
        if (word.empty()) return;
        const std::string candidate = current.empty() ? word : current + " " + word;
        if (!current.empty() && measure(candidate) > width) {
            lines.push_back(current);
            current = word;
        } else {
            current = candidate;
        }
        word.clear();
    };
    for (char c : flattened) {
        if (c == '\n') {
            flush_word();
            lines.push_back(current);
            current.clear();
            continue;
        }
        if (c == ' ') {
            flush_word();
            continue;
        }
        word.push_back(c);
    }
    flush_word();
    if (!current.empty()) lines.push_back(current);
    if (lines.empty()) lines.push_back(std::string());
    return lines;
}

float measure_text(const Ogre::FontPtr& font, const std::string& text, float char_height,
                   float across, float down) {
    if (!font) return 0.0f;
    const float coefficient = across > 0.0f ? down / across : 1.0f;
    float total = 0.0f;
    for (std::size_t i = 0; i < text.size();) {
        total += font->getGlyphInfo(next_code_point(text, i)).advance * char_height * coefficient;
    }
    return total;
}

std::vector<std::vector<TextSpan>> wrap_runs(const Ogre::FontPtr& font,
                                             const std::vector<tl::core::MarkupRun>& runs,
                                             float width, float char_height, float across,
                                             float down) {
    std::vector<std::vector<TextSpan>> lines;
    std::vector<TextSpan> current;
    float pen = 0.0f;

    const auto advance = [&](const std::string& text) {
        return measure_text(font, text, char_height, across, down);
    };

    const auto break_line = [&]() {
        lines.push_back(current);
        current.clear();
        pen = 0.0f;
    };

    // Append `word` in run `run`, opening a new span whenever the colour
    // changes or the line has just been broken. `lead` is the space that goes
    // before it, which is dropped at the start of a line.
    const auto put = [&](const tl::core::MarkupRun& run, const std::string& lead,
                         const std::string& word) {
        const std::string piece = lead + word;
        const bool same = !current.empty() && current.back().coloured == run.coloured &&
                          current.back().a == run.a && current.back().r == run.r &&
                          current.back().g == run.g && current.back().b == run.b;
        if (same) {
            current.back().text += piece;
        } else {
            TextSpan span;
            span.text = piece;
            span.x = pen;
            span.coloured = run.coloured;
            span.a = run.a;
            span.r = run.r;
            span.g = run.g;
            span.b = run.b;
            current.push_back(span);
        }
        pen += advance(piece);
    };

    // Carried across runs, not reset per run: a colour run opens mid-sentence, so the space
    // ending one run separates it from the next.
    bool pending_space = false;

    for (const tl::core::MarkupRun& run : runs) {
        // The authored text carries a **literal backslash-n**; both it and a
        // real newline break a line, exactly as wrap_text() has it.
        std::string flattened;
        flattened.reserve(run.text.size());
        for (size_t i = 0; i < run.text.size(); ++i) {
            if (run.text[i] == '\\' && i + 1 < run.text.size() && run.text[i + 1] == 'n') {
                flattened.push_back('\n');
                ++i;
                continue;
            }
            flattened.push_back(run.text[i]);
        }

        std::string word;
        const auto flush_word = [&]() {
            if (word.empty()) {
                // A space that ends a run still separates it from the next.
                return;
            }
            const std::string lead = (pending_space && !current.empty()) ? " " : std::string();
            if (!current.empty() && pen + advance(lead + word) > width) {
                break_line();
                put(run, std::string(), word);
            } else {
                put(run, lead, word);
            }
            word.clear();
            pending_space = false;
        };

        for (char c : flattened) {
            if (c == '\n') {
                flush_word();
                break_line();
                pending_space = false;
                continue;
            }
            if (c == ' ') {
                flush_word();
                pending_space = true;
                continue;
            }
            word.push_back(c);
        }
        flush_word();
    }

    if (!current.empty()) lines.push_back(current);
    if (lines.empty()) lines.emplace_back();
    return lines;
}

}  // namespace tl::render
