#include "t9_string_utils.h"

#include <cctype>

namespace rime {

int CountLetters(const std::string& s) {
    int count = 0;
    for (char c : s) {
        if (std::isalpha(static_cast<unsigned char>(c))) ++count;
    }
    return count;
}

int CountLetters(const std::optional<std::string>& s) {
    if (!s.has_value()) return 0;
    return CountLetters(*s);
}

bool StartsWith(const std::string& s, const std::string& prefix) {
    return s.size() >= prefix.size() &&
           s.compare(0, prefix.size(), prefix) == 0;
}

bool EndsWith(const std::string& s, const std::string& suffix) {
    return s.size() >= suffix.size() &&
           s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string Drop(const std::string& s, int n) {
    if (n <= 0) return s;
    if (n >= static_cast<int>(s.size())) return "";
    return s.substr(n);
}

std::string Take(const std::string& s, int n) {
    if (n <= 0) return "";
    if (n >= static_cast<int>(s.size())) return s;
    return s.substr(0, n);
}

std::string DropLast(const std::string& s, int n) {
    if (n <= 0) return s;
    if (n >= static_cast<int>(s.size())) return "";
    return s.substr(0, s.size() - n);
}

std::string FilterLetters(const std::string& s) {
    std::string result;
    for (char c : s) {
        if (std::isalpha(static_cast<unsigned char>(c))) result.push_back(c);
    }
    return result;
}

std::string JoinPinyins(const std::vector<SyllableOption>& sels) {
    std::string result;
    for (const auto& sel : sels) {
        result += sel.pinyin;
    }
    return result;
}

}  // namespace rime
