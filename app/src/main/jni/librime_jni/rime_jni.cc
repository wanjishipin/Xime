// Xime Rime JNI 接口
// 基于 trime 的实现

#include <rime_api.h>
#include <rime/setup.h>
#include <rime/dict/reverse_lookup_dictionary.h>
#include <jni.h>
#include <android/log.h>
#include <memory>
#include <string>
#include <vector>
#include <unistd.h>  // for usleep
#include <cstring>   // for strcmp
#include <utility>   // for std::pair
#include <ctime>     // for time

#define LOG_TAG "XimeRime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();

static void declare_librime_module_dependencies() {
  rime_require_module_lua();
  rime_require_module_octagram();
  rime_require_module_predict();
}

struct ProcessResult {
    bool processed;
    std::string committedText;
    std::string inputText;
    std::string preeditText;
    std::vector<std::pair<std::string, std::string>> candidates;
    bool isAsciiMode;
    bool hasNextPage;
    bool hasPrevPage;
};

struct CompositionResult {
    std::string input;
    std::string preedit;
    std::string committedText;
    std::vector<std::pair<std::string, std::string>> candidates;
    bool isAsciiMode;
    bool hasNextPage;
    bool hasPrevPage;
};

// Rime 单例类
class Rime {
public:
    Rime() : rime(rime_get_api()) {}
    Rime(Rime const&) = delete;
    void operator=(Rime const&) = delete;

    static Rime& Instance() {
        static Rime instance;
        return instance;
    }

    void startup(const char* user_data_dir, const char* shared_data_dir) {
        if (!rime) {
            LOGE("Rime API not available");
            return;
        }

        declare_librime_module_dependencies();

        user_data_dir_ = user_data_dir;
        shared_data_dir_ = shared_data_dir;

        std::string log_dir = std::string(user_data_dir) + "/logs";
        
        RIME_STRUCT(RimeTraits, traits);
        traits.shared_data_dir = shared_data_dir;
        traits.user_data_dir = user_data_dir;
        traits.log_dir = log_dir.c_str();
        traits.min_log_level = 1;
        traits.app_name = "rime.kime";
        traits.distribution_name = "Xime";
        traits.distribution_code_name = "kime";
        traits.distribution_version = "1.0.0";

        LOGI("Setting up Rime with shared_data_dir=%s, user_data_dir=%s, log_dir=%s", 
             shared_data_dir, user_data_dir, log_dir.c_str());
        
        rime->setup(&traits);
        LOGI("Rime setup completed");
        
        rime->initialize(&traits);
        LOGI("Rime initialize completed");
        
        // NOTE: start_maintenance 不再在 startup 中调用。
        // 从 Kotlin 侧根据词库文件是否已存在，按需调用 startMaintenance()。
        // 避免每次切换输入法时都触发 librime 的部署流程。
    }

    Bool startMaintenance(bool full) {
        if (!rime) {
            LOGE("startMaintenance: rime not available");
            return false;
        }
        LOGI("Starting maintenance (full=%s)...", full ? "true" : "false");
        Bool result = rime->start_maintenance(full);
        if (!result) {
            LOGE("startMaintenance FAILED: rime->start_maintenance() returned false");
        }
        return result;
    }

    bool createSession() {
        if (!rime) return false;
        session_id_ = rime->create_session();
        if (session_id_ != 0) {
            LOGI("Session created: %lu", (unsigned long)session_id_);
        } else {
            LOGD("Session creation failed (engine may be maintaining)");
        }
        return session_id_ != 0;
    }

    bool hasSession() {
        return session_id_ != 0;
    }

    bool isMaintaining() {
        if (!rime) return false;
        // librime API 使用 is_maintenance_mode
        return rime->is_maintenance_mode();
    }

    std::string getCurrentSchema() {
        if (!rime || !session_id_) return "";
        
        // get_current_schema 需要 buffer 和 buffer_size 参数
        char buffer[256];
        if (rime->get_current_schema(session_id_, buffer, sizeof(buffer))) {
            return std::string(buffer);
        }
        return "";
    }

    bool processKey(int keycode, int mask) {
        if (!rime || !session_id_) {
            LOGE("processKey: rime or session not available");
            return false;
        }
        LOGD("processKey: keycode=%d, mask=%d", keycode, mask);
        bool result = rime->process_key(session_id_, keycode, mask);
        LOGD("processKey result: %d", result);
        return result;
    }

    ProcessResult processKeyAndGetResult(int keycode, int mask) {
        ProcessResult result;
        result.processed = false;
        result.isAsciiMode = false;
        result.hasNextPage = false;
        result.hasPrevPage = false;

        if (!rime || !session_id_) {
            LOGE("processKeyAndGetResult: rime or session not available");
            return result;
        }

        result.processed = rime->process_key(session_id_, keycode, mask);
        readCurrentState(result);
        return result;
    }

    ProcessResult readResult(bool processed) {
        ProcessResult result;
        result.processed = processed;
        result.isAsciiMode = false;
        result.hasNextPage = false;
        result.hasPrevPage = false;
        if (!rime || !session_id_) {
            LOGE("readResult: rime or session not available");
            return result;
        }
        readCurrentState(result);
        return result;
    }

    void readCurrentState(ProcessResult& result) {
        RIME_STRUCT(RimeCommit, commit);
        if (rime->get_commit(session_id_, &commit)) {
            result.committedText = commit.text ? commit.text : "";
            rime->free_commit(&commit);
        }

    RIME_STRUCT(RimeContext, context);
    if (rime->get_context(session_id_, &context)) {
        const char* input = rime->get_input(session_id_);
        result.inputText = input ? input : "";
        result.preeditText = context.composition.preedit ?
            context.composition.preedit : "";
        if (context.menu.num_candidates > 0) {
            for (int i = 0; i < context.menu.num_candidates; ++i) {
                const char* text = context.menu.candidates[i].text;
                const char* comment = context.menu.candidates[i].comment;
                result.candidates.push_back(std::make_pair(
                    text ? text : "",
                    comment ? comment : ""
                ));
            }
        }
        result.hasNextPage = !context.menu.is_last_page;
        result.hasPrevPage = context.menu.page_no > 0;
        rime->free_context(&context);
    } else {
        const char* input = rime->get_input(session_id_);
        result.inputText = input ? input : "";
        result.preeditText = "";
    }

        RIME_STRUCT(RimeStatus, status);
        if (rime->get_status(session_id_, &status)) {
            result.isAsciiMode = status.is_ascii_mode;
            rime->free_status(&status);
        }
    }

    bool setInput(const char* input) {
        if (!rime || !session_id_) {
            LOGE("setInput: rime or session not available");
            return false;
        }
        if (!input || strlen(input) == 0) {
            LOGD("setInput: empty input, clearing composition");
            rime->clear_composition(session_id_);
            return true;
        }
        LOGD("setInput: '%s'", input);
        return rime->set_input(session_id_, input);
    }

    CompositionResult getComposition() {
        CompositionResult result;
        if (!rime || !session_id_) {
            LOGE("getComposition: rime or session not available");
            return result;
        }

        // 1. raw input
        const char* input = rime->get_input(session_id_);
        result.input = input ? input : "";

        // 2. context: preedit + candidates + pagination
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            if (context.composition.preedit) {
                result.preedit = context.composition.preedit;
            }
            for (int i = 0; i < context.menu.num_candidates; ++i) {
                const char* text = context.menu.candidates[i].text;
                const char* comment = context.menu.candidates[i].comment;
                result.candidates.push_back(std::make_pair(
                    text ? text : "",
                    comment ? comment : ""
                ));
            }
            result.hasNextPage = !context.menu.is_last_page;
            result.hasPrevPage = context.menu.page_no > 0;
            rime->free_context(&context);
        }

        // 3. commit text（统一返回，避免调用方额外查询）
        RIME_STRUCT(RimeCommit, commit);
        if (rime->get_commit(session_id_, &commit)) {
            result.committedText = commit.text ? commit.text : "";
            rime->free_commit(&commit);
        }

        // 4. status: ascii mode
        RIME_STRUCT(RimeStatus, status);
        if (rime->get_status(session_id_, &status)) {
            result.isAsciiMode = status.is_ascii_mode;
            rime->free_status(&status);
        }

        return result;
    }

    const char* getInput() {
        if (!rime || !session_id_) return "";
        const char* input = rime->get_input(session_id_);
        LOGD("getInput: '%s'", input ? input : "(null)");
        return input ? input : "";
    }

    void getCandidates(std::vector<std::string>& candidates) {
        if (!rime || !session_id_) return;
        
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            LOGD("getCandidates: num_candidates=%d", context.menu.num_candidates);
            if (context.menu.num_candidates > 0) {
                for (int i = 0; i < context.menu.num_candidates; ++i) {
                    const char* text = context.menu.candidates[i].text;
                    LOGD("Candidate %d: '%s'", i, text ? text : "(null)");
                    candidates.push_back(text ? text : "");
                }
            }
            rime->free_context(&context);
        } else {
            LOGD("getCandidates: no context available");
        }
    }

    void getCandidatesWithComments(std::vector<std::pair<std::string, std::string>>& candidates) {
        if (!rime || !session_id_) return;
        
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            LOGD("getCandidatesWithComments: num_candidates=%d", context.menu.num_candidates);
            if (context.menu.num_candidates > 0) {
                for (int i = 0; i < context.menu.num_candidates; ++i) {
                    const char* text = context.menu.candidates[i].text;
                    const char* comment = context.menu.candidates[i].comment;
                    candidates.push_back(std::make_pair(
                        text ? text : "",
                        comment ? comment : ""
                    ));
                }
            }
            rime->free_context(&context);
        }
    }

    bool selectCandidate(int index) {
        if (!rime || !session_id_) return false;
        return rime->select_candidate_on_current_page(session_id_, index);
    }
    
    bool pageDown() {
        if (!rime || !session_id_) return false;
        return rime->process_key(session_id_, 0xFF56, 0);
    }
    
    bool pageUp() {
        if (!rime || !session_id_) return false;
        return rime->process_key(session_id_, 0xFF55, 0);
    }
    
    bool hasNextPage() {
        if (!rime || !session_id_) return false;
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            bool result = context.menu.page_no < context.menu.page_no + 1;
            rime->free_context(&context);
            return result;
        }
        return false;
    }
    
    bool hasPrevPage() {
        if (!rime || !session_id_) return false;
        RIME_STRUCT(RimeContext, context);
        if (rime->get_context(session_id_, &context)) {
            bool result = context.menu.page_no > 0;
            rime->free_context(&context);
            return result;
        }
        return false;
    }

    std::string commit() {
        std::string result;
        if (!rime || !session_id_) return result;
        
        RIME_STRUCT(RimeCommit, commit);
        if (rime->get_commit(session_id_, &commit)) {
            result = commit.text ? commit.text : "";
            LOGD("commit: '%s'", result.c_str());
            rime->free_commit(&commit);
        }
        return result;
    }

    void clearComposition() {
        if (!rime || !session_id_) return;
        rime->clear_composition(session_id_);
    }

    bool toggleAsciiMode() {
        if (!rime || !session_id_) {
            LOGE("toggleAsciiMode: rime or session not available");
            return false;
        }
        
        // 获取当前 ascii_mode 状态
        RIME_STRUCT(RimeStatus, status);
        if (!rime->get_status(session_id_, &status)) {
            LOGE("toggleAsciiMode: failed to get status");
            return false;
        }
        
        bool current_ascii_mode = status.is_ascii_mode;
        rime->free_status(&status);
        
        LOGI("toggleAsciiMode: current ascii_mode=%s", current_ascii_mode ? "true" : "false");
        
        // 切换状态
        bool new_ascii_mode = !current_ascii_mode;
        
        // 使用 set_option 来设置 ascii_mode
        rime->set_option(session_id_, "ascii_mode", new_ascii_mode);
        
        // 验证是否设置成功
        RIME_STRUCT(RimeStatus, new_status);
        if (rime->get_status(session_id_, &new_status)) {
            bool result = new_status.is_ascii_mode == new_ascii_mode;
            LOGI("toggleAsciiMode: new ascii_mode=%s, result=%s",
                 new_status.is_ascii_mode ? "true" : "false",
                 result ? "success" : "failed");
            rime->free_status(&new_status);
            return result;
        }
        
        return true;
    }

    bool isAsciiMode() {
        if (!rime || !session_id_) return false;
        
        RIME_STRUCT(RimeStatus, status);
        if (rime->get_status(session_id_, &status)) {
            bool result = status.is_ascii_mode;
            rime->free_status(&status);
            return result;
        }
        return false;
    }

    bool switchSchema(const char* schema_id) {
        if (!rime || !session_id_) {
            LOGE("switchSchema: rime or session not available");
            return false;
        }
        
        LOGI("switchSchema: switching to '%s'", schema_id);
        
        // 直接切换方案，不验证方案是否存在（get_schema_list 不读 default.custom.yaml 的 patch）
        bool result = rime->select_schema(session_id_, schema_id);
        LOGI("select_schema result: %s", result ? "true" : "false");
        
        if (result) {
            // 验证切换是否成功
            char current_schema[256];
            if (rime->get_current_schema(session_id_, current_schema, sizeof(current_schema))) {
                LOGI("Current schema after switch: %s", current_schema);
                return strcmp(current_schema, schema_id) == 0;
            }
        }
        
        return result;
    }

    void getAvailableSchemas(std::vector<std::pair<std::string, std::string>>& schemas) {
        if (!rime) return;
        
        RimeSchemaList schema_list = {0};
        if (rime->get_schema_list(&schema_list)) {
            LOGI("Available schemas: %zu", schema_list.size);
            for (size_t i = 0; i < schema_list.size; i++) {
                std::string id = schema_list.list[i].schema_id ? schema_list.list[i].schema_id : "";
                std::string name = schema_list.list[i].name ? schema_list.list[i].name : "";
                schemas.push_back(std::make_pair(id, name));
                LOGI("  Schema %zu: %s (%s)", i, id.c_str(), name.c_str());
            }
            rime->free_schema_list(&schema_list);
        } else {
            LOGD("No schemas available yet (deployment may still be running)");
        }
    }
    
    // 查找词汇的编码
    // 使用 reverse_lookup_dictionary API 反查字符编码
    bool lookupText(const char* text, std::string& outCode) {
        if (!rime || !text) return false;
        LOGD("lookupText: word='%s'", text);
        
        auto* component = rime::ReverseLookupDictionary::Require("reverse_lookup_dictionary");
        if (!component) { LOGD("lookupText: component not available"); return false; }
        auto* rldc = dynamic_cast<rime::ReverseLookupDictionaryComponent*>(component);
        if (!rldc) { LOGD("lookupText: not ReverseLookupDictionaryComponent"); return false; }
        
        // 先查当前 schema 使用的字典
        if (session_id_) {
            char schema_id[256] = {0};
            if (rime->get_current_schema(session_id_, schema_id, sizeof(schema_id))) {
                RimeConfig config = {0};
                if (rime->schema_open(schema_id, &config)) {
                    const char* dict = rime->config_get_cstring(&config, "translator/dictionary");
                    if (dict) {
                        LOGD("lookupText: schema '%s' uses dict '%s'", schema_id, dict);
                        auto d = rldc->Create(dict);
                        if (d && d->Load()) {
                            std::string r;
                            if (d->ReverseLookup(text, &r)) { outCode = r; delete d; rime->config_close(&config); return true; }
                        }
                        delete d;
                    }
                    // 也查 reverse_lookup 字典
                    const char* rev = rime->config_get_cstring(&config, "reverse_lookup/dictionary");
                    if (rev) {
                        LOGD("lookupText: schema '%s' reverse_lookup dict '%s'", schema_id, rev);
                        auto d = rldc->Create(rev);
                        if (d && d->Load()) {
                            std::string r;
                            if (d->ReverseLookup(text, &r)) { outCode = r; delete d; rime->config_close(&config); return true; }
                        }
                        delete d;
                    }
                    rime->config_close(&config);
                }
            }
        }
        
        // fallback: 依次尝试已知编码字典
        const char* fallbacks[] = {"wubi86", "pinyin_simp", nullptr};
        for (int i = 0; fallbacks[i]; i++) {
            auto d = rldc->Create(fallbacks[i]);
            if (!d) continue;
            if (d->Load()) {
                std::string r;
                if (d->ReverseLookup(text, &r)) { outCode = r; delete d; return true; }
            }
            delete d;
        }
        
        return false;
    }
    
    bool deploy() {
        if (!rime) {
            LOGE("deploy: rime not available");
            return false;
        }
        
        LOGI("Starting deployment...");
        
        // 先销毁旧session
        if (session_id_) {
            LOGI("Destroying old session before deployment");
            rime->destroy_session(session_id_);
            session_id_ = 0;
        }
        
        // 删除 installation.yaml 以强制触发完整编译
        // librime 的 RimeStartMaintenance 中 installation_update 任务在
        // 检测到 installation.yaml 已存在且版本匹配时会返回 false，
        // 导致不调度任何编译任务直接返回
        std::string install_yaml(user_data_dir_ + "/installation.yaml");
        if (access(install_yaml.c_str(), F_OK) == 0) {
            LOGI("Removing existing installation.yaml to force full deployment");
            remove(install_yaml.c_str());
        }
        
        rime->start_maintenance(true);
        
        // 等待部署完成（不设超时，大词库编译可能很久）
        int wait_count = 0;
        while (rime->is_maintenance_mode()) {
            usleep(100000);  // 100ms
            wait_count++;
            if (wait_count % 10 == 0) {
                LOGI("Waiting for deployment... (%d seconds)", wait_count / 10);
            }
        }
        
        if (rime->is_maintenance_mode()) {
            LOGE("Deployment timeout!");
            return false;
        }
        
        // 重新创建session
        LOGI("Creating new session after deployment");
        session_id_ = rime->create_session();
        if (!session_id_) {
            LOGE("Failed to create session after deployment");
            return false;
        }
        LOGI("New session created: %lu", (unsigned long)session_id_);
        
        LOGI("Deployment completed successfully");
        return true;
    }
    
    bool deploySchema(const char* schemaId) {
        if (!rime) {
            LOGE("deploySchema: rime not available");
            return false;
        }
        
        // 确保部署模块已加载（schema_update 等任务注册在 levers 模块中）
        rime::LoadModules(rime::kDeployerModules);
        
        // 构造 .schema.yaml 文件名
        std::string schemaFile(schemaId);
        if (schemaFile.find(".schema.yaml") == std::string::npos) {
            schemaFile += ".schema.yaml";
        }
        
        // 在 user_data_dir 和 shared_data_dir 中查找 schema 文件
        std::string schemaPath;
        std::string userPath = user_data_dir_ + "/" + schemaFile;
        std::string sharedPath = shared_data_dir_ + "/" + schemaFile;
        if (access(userPath.c_str(), F_OK) == 0) {
            schemaPath = userPath;
        } else if (access(sharedPath.c_str(), F_OK) == 0) {
            schemaPath = sharedPath;
        } else {
            LOGE("deploySchema: schema file not found at %s or %s",
                 userPath.c_str(), sharedPath.c_str());
            return false;
        }
        
        LOGI("Deploying single schema: %s", schemaPath.c_str());
        
        // 先销毁旧session
        if (session_id_) {
            rime->destroy_session(session_id_);
            session_id_ = 0;
        }
        
        Bool result = rime->deploy_schema(schemaPath.c_str());
        if (!result) {
            LOGE("deploy_schema failed for: %s", schemaPath.c_str());
            // 回退：启动完整维护等待完成
            rime->start_maintenance(true);
            while (rime->is_maintenance_mode()) {
                usleep(100000);
            }
        }
        
        // 重新创建session
        session_id_ = rime->create_session();
        LOGI("Deploy schema completed: %s", schemaId);
        return true;
    }

    void updateLastBuildTime() {
        if (!rime) return;
        RimeConfig config;
        if (rime->config_open("user", &config)) {
            int now = (int)(time(nullptr));
            rime->config_set_int(&config, "var/last_build_time", now);
            LOGI("Updated last_build_time to %d", now);
            rime->config_close(&config);
        }
    }

    void setPageSize(const char* schema_id, int page_size) {
        if (!rime) {
            LOGE("setPageSize: rime not available");
            return;
        }
        // schema_open 直接打开方案的配置对象，修改内存中的 menu/page_size
        RimeConfig config;
        if (rime->schema_open(schema_id, &config)) {
            rime->config_set_int(&config, "menu/page_size", page_size);
            rime->config_close(&config);
            LOGI("Set schema '%s' menu/page_size=%d via schema_open", schema_id, page_size);
        } else {
            LOGE("setPageSize: schema_open failed for '%s'", schema_id);
        }
    }

    void setOption(const char* option, Bool value) {
        if (!rime || !session_id_) {
            LOGE("setOption: rime or session not available");
            return;
        }
        rime->set_option(session_id_, option, value);
        LOGI("setOption: %s = %s", option, value ? "true" : "false");
    }

    Bool getOption(const char* option) {
        if (!rime || !session_id_) {
            LOGE("getOption: rime or session not available");
            return false;
        }
        Bool result = rime->get_option(session_id_, option);
        LOGD("getOption: %s = %s", option, result ? "true" : "false");
        return result;
    }

    void destroy() {
        if (rime) {
            if (session_id_) {
                rime->destroy_session(session_id_);
                session_id_ = 0;
            }
            rime->finalize();
        }
    }

private:
    RimeApi* rime;
    RimeSessionId session_id_ = 0;
    std::string user_data_dir_;
    std::string shared_data_dir_;
};

extern "C" {

static jclass gRimeProcessResultClass = nullptr;
static jmethodID gRimeProcessResultCtor = nullptr;
static jclass gRimeCompositionClass = nullptr;
static jmethodID gRimeCompositionCtor = nullptr;
static jclass gRimeCandidateClass = nullptr;
static jmethodID gRimeCandidateCtor = nullptr;

static void ensureJniCache(JNIEnv* env) {
    if (!gRimeCandidateClass) {
        jclass cls = env->FindClass("com/kingzcheung/xime/rime/RimeCandidate");
        gRimeCandidateClass = (jclass)env->NewGlobalRef(cls);
        gRimeCandidateCtor = env->GetMethodID(gRimeCandidateClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
    }
    if (!gRimeProcessResultClass) {
        jclass cls = env->FindClass("com/kingzcheung/xime/rime/RimeProcessResult");
        gRimeProcessResultClass = (jclass)env->NewGlobalRef(cls);
        gRimeProcessResultCtor = env->GetMethodID(gRimeProcessResultClass, "<init>",
            "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lcom/kingzcheung/xime/rime/RimeCandidate;ZZZ)V");
        env->DeleteLocalRef(cls);
    }
    if (!gRimeCompositionClass) {
        jclass cls = env->FindClass("com/kingzcheung/xime/rime/RimeComposition");
        gRimeCompositionClass = (jclass)env->NewGlobalRef(cls);
        gRimeCompositionCtor = env->GetMethodID(gRimeCompositionClass, "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lcom/kingzcheung/xime/rime/RimeCandidate;ZZZ)V");
        env->DeleteLocalRef(cls);
    }
}

// 初始化 Rime 引擎
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeInitialize(
    JNIEnv* env,
    jobject thiz,
    jstring user_data_dir,
    jstring shared_data_dir
) {
    const char* user_dir = env->GetStringUTFChars(user_data_dir, nullptr);
    const char* shared_dir = env->GetStringUTFChars(shared_data_dir, nullptr);
    
    LOGI("Initializing Rime engine with user_dir=%s, shared_dir=%s", user_dir, shared_dir);
    Rime::Instance().startup(user_dir, shared_dir);
    
    env->ReleaseStringUTFChars(user_data_dir, user_dir);
    env->ReleaseStringUTFChars(shared_data_dir, shared_dir);
}

// 创建会话（startup 只初始化引擎，session 延迟创建）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeCreateSession(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().createSession() ? JNI_TRUE : JNI_FALSE;
}

// 检查会话是否存在
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHasSession(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().hasSession() ? JNI_TRUE : JNI_FALSE;
}

// 检查是否正在维护
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeIsMaintaining(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().isMaintaining() ? JNI_TRUE : JNI_FALSE;
}

// 获取当前方案
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCurrentSchema(
    JNIEnv* env,
    jobject thiz
) {
    std::string schema = Rime::Instance().getCurrentSchema();
    return env->NewStringUTF(schema.c_str());
}

// 处理按键输入
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeProcessKey(
    JNIEnv* env,
    jobject thiz,
    jint keycode,
    jint mask
) {
    return Rime::Instance().processKey(keycode, mask) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeProcessKeyAndGetResult(
    JNIEnv* env,
    jobject thiz,
    jint keycode,
    jint mask
) {
    ensureJniCache(env);

    ProcessResult result = Rime::Instance().processKeyAndGetResult(keycode, mask);

    jobjectArray candidateArray = env->NewObjectArray(
        result.candidates.size(), gRimeCandidateClass, nullptr);

    for (size_t i = 0; i < result.candidates.size(); ++i) {
        jstring text = env->NewStringUTF(result.candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(result.candidates[i].second.c_str());
        jobject candidate = env->NewObject(gRimeCandidateClass, gRimeCandidateCtor, text, comment);
        env->SetObjectArrayElement(candidateArray, i, candidate);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(candidate);
    }

    jstring jCommitted = env->NewStringUTF(result.committedText.c_str());
    jstring jInput = env->NewStringUTF(result.inputText.c_str());
    jstring jPreedit = env->NewStringUTF(result.preeditText.c_str());

    jobject jResult = env->NewObject(gRimeProcessResultClass, gRimeProcessResultCtor,
        result.processed ? JNI_TRUE : JNI_FALSE,
        jCommitted,
        jInput,
        jPreedit,
        candidateArray,
        result.isAsciiMode ? JNI_TRUE : JNI_FALSE,
        result.hasNextPage ? JNI_TRUE : JNI_FALSE,
        result.hasPrevPage ? JNI_TRUE : JNI_FALSE);

    env->DeleteLocalRef(jCommitted);
    env->DeleteLocalRef(jInput);
    env->DeleteLocalRef(jPreedit);
    env->DeleteLocalRef(candidateArray);

    return jResult;
}

JNIEXPORT jobject JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetProcessResult(
    JNIEnv* env,
    jobject thiz,
    jboolean processed
) {
    ensureJniCache(env);

    ProcessResult result = Rime::Instance().readResult(processed);

    jobjectArray candidateArray = env->NewObjectArray(
        result.candidates.size(), gRimeCandidateClass, nullptr);

    for (size_t i = 0; i < result.candidates.size(); ++i) {
        jstring text = env->NewStringUTF(result.candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(result.candidates[i].second.c_str());
        jobject candidate = env->NewObject(gRimeCandidateClass, gRimeCandidateCtor, text, comment);
        env->SetObjectArrayElement(candidateArray, i, candidate);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(candidate);
    }

    jstring jCommitted = env->NewStringUTF(result.committedText.c_str());
    jstring jInput = env->NewStringUTF(result.inputText.c_str());
    jstring jPreedit = env->NewStringUTF(result.preeditText.c_str());

    jobject jResult = env->NewObject(gRimeProcessResultClass, gRimeProcessResultCtor,
        result.processed ? JNI_TRUE : JNI_FALSE,
        jCommitted,
        jInput,
        jPreedit,
        candidateArray,
        result.isAsciiMode ? JNI_TRUE : JNI_FALSE,
        result.hasNextPage ? JNI_TRUE : JNI_FALSE,
        result.hasPrevPage ? JNI_TRUE : JNI_FALSE);

    env->DeleteLocalRef(jCommitted);
    env->DeleteLocalRef(jInput);
    env->DeleteLocalRef(jPreedit);
    env->DeleteLocalRef(candidateArray);

    return jResult;
}

// 设置输入字符串（替代逐字符 processKey，减少 JNI 调用次数）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetInput(
    JNIEnv* env,
    jobject thiz,
    jstring input
) {
    const char* input_str = env->GetStringUTFChars(input, nullptr);
    if (!input_str) {
        LOGE("nativeSetInput: null input");
        return JNI_FALSE;
    }
    bool result = Rime::Instance().setInput(input_str);
    env->ReleaseStringUTFChars(input, input_str);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 一次性获取当前 composition 全部信息：input/preedit/commit/candidates/paging/ascii_mode
// 将 updateUI 所需的多次 JNI 查询合并为一次，减少 JNI 往返开销。
JNIEXPORT jobject JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetComposition(
    JNIEnv* env,
    jobject thiz
) {
    ensureJniCache(env);

    CompositionResult result = Rime::Instance().getComposition();

    jobjectArray candidateArray = env->NewObjectArray(
        result.candidates.size(), gRimeCandidateClass, nullptr);

    for (size_t i = 0; i < result.candidates.size(); ++i) {
        jstring text = env->NewStringUTF(result.candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(result.candidates[i].second.c_str());
        jobject candidate = env->NewObject(gRimeCandidateClass, gRimeCandidateCtor, text, comment);
        env->SetObjectArrayElement(candidateArray, i, candidate);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(candidate);
    }

    jstring jInput = env->NewStringUTF(result.input.c_str());
    jstring jPreedit = env->NewStringUTF(result.preedit.c_str());
    jstring jCommitted = env->NewStringUTF(result.committedText.c_str());

    jobject jComposition = env->NewObject(gRimeCompositionClass, gRimeCompositionCtor,
        jInput,
        jPreedit,
        jCommitted,
        candidateArray,
        result.hasNextPage ? JNI_TRUE : JNI_FALSE,
        result.hasPrevPage ? JNI_TRUE : JNI_FALSE,
        result.isAsciiMode ? JNI_TRUE : JNI_FALSE);

    env->DeleteLocalRef(jInput);
    env->DeleteLocalRef(jPreedit);
    env->DeleteLocalRef(jCommitted);
    env->DeleteLocalRef(candidateArray);

    return jComposition;
}

// 获取候选词列表
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCandidates(
    JNIEnv* env,
    jobject thiz
) {
    std::vector<std::string> candidates;
    Rime::Instance().getCandidates(candidates);
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(candidates.size(), stringClass, nullptr);
    
    for (size_t i = 0; i < candidates.size(); ++i) {
        jstring str = env->NewStringUTF(candidates[i].c_str());
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }
    
    return result;
}

// 获取候选词列表（包含编码注释）
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetCandidatesWithComments(
    JNIEnv* env,
    jobject thiz
) {
    std::vector<std::pair<std::string, std::string>> candidates;
    Rime::Instance().getCandidatesWithComments(candidates);
    
    jclass stringClass = env->FindClass("java/lang/String");
    jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
    
    jobjectArray result = env->NewObjectArray(candidates.size(), stringArrayClass, nullptr);
    
    for (size_t i = 0; i < candidates.size(); ++i) {
        jobjectArray pair = env->NewObjectArray(2, stringClass, nullptr);
        jstring text = env->NewStringUTF(candidates[i].first.c_str());
        jstring comment = env->NewStringUTF(candidates[i].second.c_str());
        env->SetObjectArrayElement(pair, 0, text);
        env->SetObjectArrayElement(pair, 1, comment);
        env->SetObjectArrayElement(result, i, pair);
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(comment);
        env->DeleteLocalRef(pair);
    }
    
    return result;
}

// 获取输入文本
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetInput(
    JNIEnv* env,
    jobject thiz
) {
    return env->NewStringUTF(Rime::Instance().getInput());
}

// 选择候选词
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSelectCandidate(
    JNIEnv* env,
    jobject thiz,
    jint index
) {
    return Rime::Instance().selectCandidate(index) ? JNI_TRUE : JNI_FALSE;
}

// 翻页 - 下一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativePageDown(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().pageDown() ? JNI_TRUE : JNI_FALSE;
}

// 翻页 - 上一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativePageUp(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().pageUp() ? JNI_TRUE : JNI_FALSE;
}

// 是否有下一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHasNextPage(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().hasNextPage() ? JNI_TRUE : JNI_FALSE;
}

// 是否有上一页
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeHasPrevPage(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().hasPrevPage() ? JNI_TRUE : JNI_FALSE;
}

// 提交文本
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeCommit(
    JNIEnv* env,
    jobject thiz
) {
    std::string text = Rime::Instance().commit();
    return env->NewStringUTF(text.c_str());
}

// 清除组合
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeClearComposition(
    JNIEnv* env,
    jobject thiz
) {
    Rime::Instance().clearComposition();
}

// 切换中英文模式（ascii_mode）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeToggleAsciiMode(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().toggleAsciiMode() ? JNI_TRUE : JNI_FALSE;
}

// 获取当前是否为英文模式
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeIsAsciiMode(
    JNIEnv* env,
    jobject thiz
) {
    return Rime::Instance().isAsciiMode() ? JNI_TRUE : JNI_FALSE;
}

// 切换输入方案
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSwitchSchema(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    bool result = Rime::Instance().switchSchema(schema);
    env->ReleaseStringUTFChars(schema_id, schema);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 获取可用方案列表
JNIEXPORT jobjectArray JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetAvailableSchemas(
    JNIEnv* env,
    jobject thiz
) {
    std::vector<std::pair<std::string, std::string>> schemas;
    Rime::Instance().getAvailableSchemas(schemas);
    
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    
    jobjectArray result = env->NewObjectArray(schemas.size(), stringClass, nullptr);
    if (!result) return nullptr;
    
    for (size_t i = 0; i < schemas.size(); ++i) {
        jstring str = env->NewStringUTF(schemas[i].first.c_str());
        env->SetObjectArrayElement(result, i, str);
        env->DeleteLocalRef(str);
    }
    
    return result;
}

// 设置 Rime 选项
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetOption(
    JNIEnv* env,
    jobject thiz,
    jstring option,
    jboolean value
) {
    const char* option_ptr = env->GetStringUTFChars(option, nullptr);
    if (!option_ptr) return;
    Rime::Instance().setOption(option_ptr, value == JNI_TRUE);
    env->ReleaseStringUTFChars(option, option_ptr);
}

// 读取 Rime 选项
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeGetOption(
    JNIEnv* env,
    jobject thiz,
    jstring option
) {
    const char* option_ptr = env->GetStringUTFChars(option, nullptr);
    if (!option_ptr) return JNI_FALSE;
    Bool result = Rime::Instance().getOption(option_ptr);
    env->ReleaseStringUTFChars(option, option_ptr);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 销毁引擎
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDestroy(
    JNIEnv* env,
    jobject thiz
) {
    LOGI("Destroying Rime engine");
    Rime::Instance().destroy();
}

// 部署
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDeploy(
    JNIEnv* env,
    jobject thiz
) {
    LOGI("Deploying Rime engine");
    return Rime::Instance().deploy() ? JNI_TRUE : JNI_FALSE;
}

// 启动维护（词库编译/刷新），返回是否成功启动部署
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeStartMaintenance(
    JNIEnv* env,
    jobject thiz,
    jboolean full
) {
    Bool result = Rime::Instance().startMaintenance(full == JNI_TRUE);
    return result ? JNI_TRUE : JNI_FALSE;
}

// 更新 last_build_time 为当前时间，避免下次增量检测误判
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeUpdateLastBuildTime(
    JNIEnv* env,
    jobject thiz
) {
    Rime::Instance().updateLastBuildTime();
}

// 部署单个方案
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeDeploySchema(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id
) {
    const char* schema_id_ptr = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema_id_ptr) return JNI_FALSE;
    
    LOGI("Deploying schema: %s", schema_id_ptr);
    
    // 构建 schema 文件路径
    Rime::Instance().deploySchema(schema_id_ptr);
    
    env->ReleaseStringUTFChars(schema_id, schema_id_ptr);
    return JNI_TRUE;
}

// 查询词汇编码
JNIEXPORT jstring JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeLookupText(
    JNIEnv* env,
    jobject thiz,
    jstring text
) {
    const char* text_ptr = env->GetStringUTFChars(text, nullptr);
    std::string code;
    bool found = Rime::Instance().lookupText(text_ptr, code);
    env->ReleaseStringUTFChars(text, text_ptr);
    
    if (found && !code.empty()) {
        return env->NewStringUTF(code.c_str());
    }
    return env->NewStringUTF("");
}

// 设置候选词每页数量
JNIEXPORT void JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeSetPageSize(
    JNIEnv* env,
    jobject thiz,
    jstring schema_id,
    jint page_size
) {
    const char* schema = env->GetStringUTFChars(schema_id, nullptr);
    if (!schema) return;
    Rime::Instance().setPageSize(schema, page_size);
    env->ReleaseStringUTFChars(schema_id, schema);
}

// 检查 Rime 模块是否已注册（用于测试插件集成）
JNIEXPORT jboolean JNICALL
Java_com_kingzcheung_xime_rime_RimeEngine_nativeIsModuleRegistered(
    JNIEnv* env,
    jobject thiz,
    jstring module_name
) {
    const char* name = env->GetStringUTFChars(module_name, nullptr);
    if (!name) return JNI_FALSE;
    
    bool found = false;
    RimeApi* api = rime_get_api();
    if (api && RIME_API_AVAILABLE(api, find_module)) {
        RimeModule* m = api->find_module(name);
        found = (m != nullptr);
    } else {
        RimeModule* m = RimeFindModule(name);
        found = (m != nullptr);
    }
    
    LOGI("Module check: '%s' -> %s", name, found ? "FOUND" : "NOT FOUND");
    env->ReleaseStringUTFChars(module_name, name);
    return found ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"