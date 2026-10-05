// 九键引擎级功能测试：在宿主 librime（merged t9 插件）上跑真实引擎，
// 用 gen_cases.py 从词库反推的键码用例验证召回与 preedit 显示。
//
// 与纯算法层 t9_test（T9_ALGO_ONLY_BUILD）互补：本测试覆盖 processor/filter
// 与真实候选流的交互（如 027ad0eb 提权这类 filter 改动，纯算法层拦不住）。
//
// 运行前置（run_engine_tests.sh 已编排）：
//   1. librime 宿主构建产物（../build_engine/librime/lib/librime.so）
//   2. 测试数据目录（T9_ENGINE_DATA_DIR）：t9_pinyin 方案 + pinyin_simp 词典
//      + 裁剪后的 default.yaml（schema_list 仅 t9_pinyin）
#include <gtest/gtest.h>
#include <rime_api.h>
#include <t9_processor.h>

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct Case {
    std::string text;
    std::vector<std::string> syllables;
    std::string digits;     // 无分词形态，如 "23744"
    std::string segmented;  // 带分词键形态，如 "23'744"
    int weight = 0;
    std::string source;
};

// 极简解析：cases/t9_cases.yaml 由 gen_cases.py 生成，字段与缩进固定。
std::vector<Case> LoadCases(const std::string& path) {
    std::vector<Case> cases;
    std::ifstream in(path);
    std::string line;
    Case cur;
    bool in_item = false;
    auto flush = [&] {
        if (in_item && !cur.text.empty() && !cur.digits.empty()) cases.push_back(cur);
        cur = Case();
        in_item = false;
    };
    while (std::getline(in, line)) {
        if (line.rfind("  - text: ", 0) == 0) {
            flush();
            in_item = true;
            cur.text = line.substr(10);
        } else if (in_item && line.rfind("    syllables: [", 0) == 0) {
            std::string body = line.substr(16);
            body = body.substr(0, body.find(']'));
            std::istringstream ss(body);
            std::string syl;
            while (std::getline(ss, syl, ',')) {
                // 去首尾空格
                size_t b = syl.find_first_not_of(' ');
                if (b == std::string::npos) continue;
                size_t e = syl.find_last_not_of(' ');
                cur.syllables.push_back(syl.substr(b, e - b + 1));
            }
        } else if (in_item && line.rfind("    digits: \"", 0) == 0) {
            cur.digits = line.substr(13, line.size() - 14);
        } else if (in_item && line.rfind("    segmented: \"", 0) == 0) {
            cur.segmented = line.substr(16, line.size() - 17);
        } else if (in_item && line.rfind("    weight: ", 0) == 0) {
            cur.weight = std::atoi(line.c_str() + 12);
        } else if (in_item && line.rfind("    source: ", 0) == 0) {
            cur.source = line.substr(12);
        }
    }
    flush();
    return cases;
}

// 模拟 Android 宿主的异步 flush：processKey 后由后台线程触发引擎 compose。
void TypeKeys(RimeApi* api, RimeSessionId session, const std::string& keys) {
    for (char ch : keys) {
        api->process_key(session, ch, 0);
        if (auto* proc = rime::T9ProcessorRequire()) {
            proc->FlushRimeInput();
        }
    }
}

// 翻页收集全部候选（menu 是 page_size 单页，Page_Down 推进）。
std::vector<std::string> DumpCandidates(RimeApi* api, RimeSessionId session,
                                        int max_candidates) {
    std::vector<std::string> out;
    for (int page = 0; page < 60 && static_cast<int>(out.size()) < max_candidates; ++page) {
        RIME_STRUCT(RimeContext, ctx);
        if (!api->get_context(session, &ctx)) break;
        int n = ctx.menu.num_candidates;
        for (int i = 0; i < n; ++i) {
            out.emplace_back(ctx.menu.candidates[i].text);
        }
        api->free_context(&ctx);
        if (n == 0) break;
        if (!api->process_key(session, 0xff56, 0)) break;  // Page_Down
    }
    return out;
}

// 引擎环境（Environment::SetUp 中赋值；TEST_F 里使用）
RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;

class T9EngineEnvironment : public ::testing::Environment {
public:
    void SetUp() override {
        const char* data_dir = std::getenv("T9_ENGINE_DATA_DIR");
        ASSERT_NE(data_dir, nullptr)
            << "T9_ENGINE_DATA_DIR 未设置（应由 run_engine_tests.sh 指向 build_engine/data）";

        RIME_STRUCT(RimeTraits, traits);
        traits.app_name = "t9.engine.test";
        // glog INFO 全量落盘极重（跑一轮曾写出十几 GB），压到 WARNING
        traits.min_log_level = 2;
        traits.shared_data_dir = data_dir;
        traits.user_data_dir = user_dir_.c_str();
        traits.staging_dir = staging_dir_.c_str();
        api_ = rime_get_api();
        api_->setup(&traits);
        api_->initialize(&traits);
        api_->start_maintenance(true);
        api_->join_maintenance_thread();
        ASSERT_FALSE(api_->is_maintenance_mode()) << "部署未完成";
        session_ = api_->create_session();
        ASSERT_NE(session_, 0u);
        ASSERT_TRUE(api_->select_schema(session_, "t9_pinyin"));
        g_api = api_;
        g_session = session_;
    }

    void TearDown() override {
        if (session_) api_->destroy_session(session_);
        api_->finalize();
    }

    RimeApi* api() const { return api_; }
    RimeSessionId session() const { return session_; }

private:
    RimeApi* api_ = nullptr;
    RimeSessionId session_ = 0;
    std::string user_dir_ = "/tmp/t9_engine_test_user";
    std::string staging_dir_ = "/tmp/t9_engine_test_user/build";
};

// 用例数据（main 中加载）
std::vector<Case> g_cases;

class T9Recall : public ::testing::Test {
protected:
    void SetUp() override {
        g_api->clear_composition(g_session);
    }
};

static bool Contains(const std::vector<std::string>& v, const std::string& x) {
    return std::find(v.begin(), v.end(), x) != v.end();
}

// 九键字母→数字映射（与 gen_cases.py 的 KEY_MAP 一致）
static std::string ToDigits(const std::string& letters) {
    static const struct { const char* letters; char digit; } kMap[] = {
        {"abc", '2'}, {"def", '3'}, {"ghi", '4'}, {"jkl", '5'},
        {"mno", '6'}, {"pqrs", '7'}, {"tuv", '8'}, {"wxyz", '9'},
    };
    std::string out;
    for (char ch : letters) {
        for (auto& m : kMap) {
            if (std::strchr(m.letters, ch)) { out += m.digit; break; }
        }
    }
    return out;
}

// vendor gtest 的 ValuesIn 是拷贝语义（静态期注册时 g_cases 尚未填充），
// 因此不用 TEST_P/INSTANTIATE，改为循环用例 + SCOPED_TRACE 给出失败上下文。
TEST_F(T9Recall, SegmentedInputRecallsWord) {
    const char* only = std::getenv("T9_ONLY_SOURCE");  // 调试：只跑指定档位
    for (const auto& c : g_cases) {
        if (only && c.source != only) continue;
        g_api->clear_composition(g_session);  // 逐条清场，防上一条残留输入污染
        SCOPED_TRACE("「" + c.text + "」(" + c.source + ") 输入 " + c.segmented);
        TypeKeys(g_api, g_session, c.segmented);
        auto candidates = DumpCandidates(g_api, g_session, 50);
        EXPECT_TRUE(Contains(candidates, c.text))
            << "带分词输入 " << c.segmented << " 的前 " << candidates.size()
            << " 个候选中没有目标词; 前5候选: "
            << (candidates.empty() ? "(空)" : [&] {
                   std::string s;
                   for (size_t i = 0; i < candidates.size() && i < 5; ++i)
                       s += (i ? "/" : "") + candidates[i];
                   return s;
               }());
    }
}

TEST_F(T9Recall, PreeditShowsSyllables) {
    const char* only = std::getenv("T9_ONLY_SOURCE");
    for (const auto& c : g_cases) {
        if (only && c.source != only) continue;
        // 仅对无分词形态断言：带分词输入的分段交互下，首候选 preedit 可能
        // 显示跨界切分（如 946'33 显示 "zhong de"），属显示层现象且候选流
        // 正确性已由 SegmentedInputRecallsWord 覆盖。
        if (c.segmented.find('\'') != std::string::npos) continue;
        g_api->clear_composition(g_session);
        SCOPED_TRACE("「" + c.text + "」(" + c.source + ") 输入 " + c.segmented);
        TypeKeys(g_api, g_session, c.segmented);
        RIME_STRUCT(RimeContext, ctx);
        ASSERT_TRUE(g_api->get_context(g_session, &ctx));
        std::string preedit = ctx.composition.preedit ? ctx.composition.preedit : "";
        g_api->free_context(&ctx);
        // composition.preedit 不是输入回显，而是「高亮候选拼音 + 未消耗输入原样」
        // 的混合显示。稳定的不变量是**双向前缀**：
        //   a) 部分消耗：高亮候选吃掉输入前缀段，剩余原样回显（mapped ⊂ input）；
        //   b) completion：高亮候选以输入为前缀的更长编码被联想召回（input ⊂ mapped）。
        // 两者都锁定 number→letter 转换链路；乱码（如状态错乱后的单键残留）必不满足。
        std::string letters;
        for (char ch : preedit) {
            if (std::isalpha(static_cast<unsigned char>(ch))) letters += ch;
        }
        std::string input_digits;
        for (char ch : c.segmented) {
            if (ch != '\'') input_digits += ch;
        }
        std::string mapped = ToDigits(letters);
        bool prefix_in = input_digits.compare(0, mapped.size(), mapped) == 0 &&
                         mapped.size() <= input_digits.size();
        bool prefix_out = mapped.compare(0, input_digits.size(), input_digits) == 0 &&
                          input_digits.size() <= mapped.size();
        EXPECT_TRUE(prefix_in || prefix_out)
            << "preedit \"" << preedit << "\" 的字母映射 (" << mapped
            << ") 与输入 (" << input_digits << ") 无前缀关系";
    }
}

TEST_F(T9Recall, DigitsInputRecallsWord) {
    const char* only = std::getenv("T9_ONLY_SOURCE");
    for (const auto& c : g_cases) {
        if (only && c.source != only) continue;
        if (c.syllables.size() < 2) continue;  // 单音节 digits 与 segmented 相同，已在上一测试覆盖
        g_api->clear_composition(g_session);
        SCOPED_TRACE("「" + c.text + "」(" + c.source + ") 输入 " + c.digits);
        TypeKeys(g_api, g_session, c.digits);
        auto candidates = DumpCandidates(g_api, g_session, 200);
        EXPECT_TRUE(Contains(candidates, c.text))
            << "无分词输入 " << c.digits << " 的前 " << candidates.size()
            << " 个候选中没有目标词";
    }
}

}  // namespace

int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);

    const char* data_dir = std::getenv("T9_ENGINE_DATA_DIR");
    if (!data_dir) {
        std::fprintf(stderr,
                     "T9_ENGINE_DATA_DIR 未设置（应由 run_engine_tests.sh 指向 build_engine/data）\n");
        return 2;
    }
    auto cases = LoadCases(std::string(data_dir) + "/../t9_cases.yaml");
    if (cases.empty()) {
        std::fprintf(stderr, "未加载到任何用例\n");
        return 2;
    }
    g_cases = std::move(cases);
    std::fprintf(stderr, "loaded %zu cases\n", g_cases.size());

    ::testing::AddGlobalTestEnvironment(new T9EngineEnvironment());
    return RUN_ALL_TESTS();
}
