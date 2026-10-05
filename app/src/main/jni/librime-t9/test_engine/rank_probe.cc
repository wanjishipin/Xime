// 深度排名探测：stdin 每行 "segmented\ttext"，dump 深层候选输出目标词排名。
#include <rime_api.h>
#include <t9_processor.h>

#include <cstdio>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

static std::vector<std::string> DumpDeep(RimeApi* api, RimeSessionId s, int max_n) {
    std::vector<std::string> out;
    for (int page = 0; page < 250 && (int)out.size() < max_n; ++page) {
        RIME_STRUCT(RimeContext, ctx);
        if (!api->get_context(s, &ctx)) break;
        int n = ctx.menu.num_candidates;
        for (int i = 0; i < n; ++i) out.emplace_back(ctx.menu.candidates[i].text);
        api->free_context(&ctx);
        if (n == 0) break;
        if (!api->process_key(s, 0xff56, 0)) break;
    }
    return out;
}

int main(int argc, char** argv) {
    const char* data_dir = std::getenv("T9_ENGINE_DATA_DIR");
    if (!data_dir) { fprintf(stderr, "need T9_ENGINE_DATA_DIR\n"); return 2; }
    RIME_STRUCT(RimeTraits, traits);
    traits.app_name = "t9.rank-probe";
    traits.shared_data_dir = data_dir;
    static std::string user = std::getenv("T9_PROBE_USER") ? std::getenv("T9_PROBE_USER") : std::string(data_dir) + "/../probe_user";
    static std::string staging = user + "/build";
    traits.user_data_dir = user.c_str();
    traits.staging_dir = staging.c_str();
    RimeApi* api = rime_get_api();
    api->setup(&traits);
    api->initialize(&traits);
    api->start_maintenance(true);
    api->join_maintenance_thread();
    if (api->is_maintenance_mode()) { fprintf(stderr, "maintenance\n"); return 2; }
    RimeSessionId session = api->create_session();
    if (!api->select_schema(session, "t9_pinyin")) { fprintf(stderr, "schema fail\n"); return 2; }

    std::string line;
    while (std::getline(std::cin, line)) {
        auto tab = line.find('\t');
        if (tab == std::string::npos) continue;
        std::string seg = line.substr(0, tab), text = line.substr(tab + 1);
        for (char ch : seg) {
            api->process_key(session, ch, 0);
            if (auto* p = rime::T9ProcessorRequire()) p->FlushRimeInput();
        }
        int cap = std::getenv("T9_DUMP_CAP") ? std::atoi(std::getenv("T9_DUMP_CAP")) : 1000;
        auto cands = DumpDeep(api, session, cap);
        int rank = -1;
        for (size_t i = 0; i < cands.size(); ++i) {
            if (cands[i] == text) { rank = (int)i + 1; break; }
        }
        printf("%s\t%s\t%d\t%zu\n", seg.c_str(), text.c_str(), rank, cands.size());
        if (std::getenv("T9_DEBUG_PREEDIT")) {
            RIME_STRUCT(RimeContext, pc);
            if (api->get_context(session, &pc)) {
                printf("    preedit=%s input_dbg\n", pc.composition.preedit ? pc.composition.preedit : "(null)");
                api->free_context(&pc);
            }
        }
        fflush(stdout);
        api->clear_composition(session);
    }
    api->destroy_session(session);
    api->finalize();
    return 0;
}
