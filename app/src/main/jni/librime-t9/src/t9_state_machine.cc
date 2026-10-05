#include "t9_state_machine.h"

namespace rime {

void T9StateMachine::EnterSelection(const SyllableOption& option,
                                     const std::string& candidate_digits,
                                     const std::string& confirmed_pinyin) {
    state_ = State::kSelection;
    selected_option_ = option;
    selection_candidate_digits_ = candidate_digits;
    confirmed_pinyin_before_selection_ = confirmed_pinyin;
    selection_history_.push_back(option);
}

void T9StateMachine::EnterIdle() {
    state_ = State::kIdle;
    selected_option_.reset();
    selection_candidate_digits_.reset();
    confirmed_pinyin_before_selection_.clear();
    selection_history_.clear();
}

void T9StateMachine::EnterInput() {
    state_ = State::kInput;
    selected_option_.reset();
    selection_candidate_digits_.reset();
    confirmed_pinyin_before_selection_.clear();
}

void T9StateMachine::RemoveLastSelectionHistoryEntry() {
    if (!selection_history_.empty()) {
        selection_history_.pop_back();
    }
}

void T9StateMachine::RemoveConsumedHistoryEntries(
    const std::string& consumed_pinyin) {
    std::string remaining = consumed_pinyin;
    while (!selection_history_.empty() && !remaining.empty()) {
        const auto& first = selection_history_.front();
        const std::string& pinyin = first.pinyin;
        if (remaining.size() >= pinyin.size() &&
            remaining.compare(0, pinyin.size(), pinyin) == 0) {
            remaining = remaining.substr(pinyin.size());
            selection_history_.erase(selection_history_.begin());
        } else {
            break;
        }
    }
}

T9StateMachine::StateSnapshot T9StateMachine::Snapshot() const {
    return StateSnapshot{
        state_,
        selected_option_,
        selection_candidate_digits_,
        confirmed_pinyin_before_selection_,
        selection_history_
    };
}

void T9StateMachine::RestoreFrom(
    State state,
    const std::optional<SyllableOption>& option,
    const std::optional<std::string>& digits,
    const std::string& confirmed_pinyin,
    const std::vector<SyllableOption>& history) {
    state_ = state;
    selected_option_ = option;
    selection_candidate_digits_ = digits;
    confirmed_pinyin_before_selection_ = confirmed_pinyin;
    selection_history_ = history;
}

}  // namespace rime
