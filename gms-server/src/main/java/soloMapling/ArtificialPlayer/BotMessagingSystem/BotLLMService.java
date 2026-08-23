package soloMapling.ArtificialPlayer.BotMessagingSystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gms.client.Character;
import soloMapling.Environment.BotConfigFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * bot 聊天的大模型（LLM）接入：OpenAI 兼容的 chat/completions 接口。
 *
 * 配置全部在服务器工作目录的 bot-config.properties（NAS 部署即挂载的持久目录）：
 *   llm_enabled     开关（false 或接口故障时回落本地台词）
 *   llm_api_url     接口地址，换模型服务商只改这里
 *   llm_model       模型名称
 *   llm_api_key     API Key
 *   llm_timeout_ms  单次请求超时（毫秒），默认 5000
 * 修改后重启服务端生效。
 *
 * 会话记忆按 bot 保存最近几轮（玩家告别/重置对话时由 SocialBot 清空），另有按 bot 的
 * 调用冷却防止刷屏烧配额。所有调用都发生在 bot 的虚拟线程上，阻塞不影响其他 bot。
 */
public final class BotLLMService {

    private static final String DEFAULT_API_URL = "https://integrate.api.nvidia.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "minimaxai/minimax-m3";
    private static final String DEFAULT_API_KEY = "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";

    private static final int HISTORY_LIMIT = 8;        // 每个 bot 保留的最近消息条数（不含 system）
    private static final long COOLDOWN_MS = 3000;      // 同一 bot 两次调用最小间隔
    private static final int MAX_REPLY_CHARS = 60;     // 聊天气泡长度限制，超长截断
    private static final int MAX_USER_CHARS = 200;     // 玩家消息过长截断

    // SM NOTE: GBK 可编码白名单，类加载时建一次（64K 布尔表，线程安全查表）。
    // 客户端聊天包走 GBK：编不出的字符会变 '?' 甚至排版错乱，这里直接在服务端剔除。
    private static final boolean[] GBK_OK = new boolean[0x10000];

    static {
        java.nio.charset.CharsetEncoder enc = java.nio.charset.Charset.forName("GBK").newEncoder();
        for (int c = 0; c < 0x10000; c++) {
            GBK_OK[c] = c >= 0x20 && enc.canEncode((char) c);
        }
    }

    // 未启用开关时只提醒一次，避免刷屏但让服主知道为什么 bot 只说固定台词
    private static volatile boolean WARNED_DISABLED = false;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // botId -> 最近对话（role/content 交替），仅在 bot 的虚拟线程上读写自己的条目
    private static final Map<Integer, Deque<String[]>> HISTORY = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_CALL = new ConcurrentHashMap<>();

    // SM NOTE: circuit breaker - when the API endpoint is down/slow every conversation stalls
    // for the full request timeout before falling back. After consecutive failures we stop
    // calling the API entirely for a while and go straight to local lines.
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_COOLDOWN_MS = 10 * 60 * 1000; // 10 min
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger(0);
    private static volatile long CIRCUIT_OPEN_UNTIL = 0;

    private BotLLMService() {
    }

    public static boolean isEnabled() {
        return "true".equalsIgnoreCase(BotConfigFile.getString("llm_enabled", "false"));
    }

    // 冷却窗口外才允许发起下一次调用（调用前先占位，防止同一 bot 并发连发）
    public static boolean ready(int botId) {
        if (!isEnabled()) {
            if (!WARNED_DISABLED) {
                WARNED_DISABLED = true;
                System.out.println("[BotLLMService] llm_enabled=false，bot 只会说本地固定台词。"
                        + "想启用大模型对话，请在服务器工作目录 bot-config.properties 里加：");
                System.out.println("[BotLLMService]   llm_enabled=true");
                System.out.println("[BotLLMService]   （可选 llm_api_url / llm_model / llm_api_key / llm_timeout_ms，"
                        + "不填用默认 NVIDIA 接口）改完重启服务端生效。");
            }
            return false;
        }
        if (System.currentTimeMillis() < CIRCUIT_OPEN_UNTIL) {
            return false; // 熔断中：API 持续失败，直接走本地台词，不卡超时
        }
        long now = System.currentTimeMillis();
        Long last = LAST_CALL.get(botId);
        return last == null || now - last >= COOLDOWN_MS;
    }

    public static void clear(int botId) {
        HISTORY.remove(botId);
    }

    /**
     * 让 bot 用大模型回复玩家的一句话。返回 null 表示不可用/失败，调用方回落本地台词。
     */
    public static String chat(int botId, Character bot, String playerName, String mapName, String userMessage) {
        return chat(botId, bot, playerName, mapName, userMessage, false);
    }

    /**
     * 同上，ignoreCooldown=true 跳过 3 秒冷却——给交易窗口这种一对一高频对话用：
     * 玩家在交易里连发几条，不能第二条起就变成固定台词。
     */
    public static String chat(int botId, Character bot, String playerName, String mapName,
                              String userMessage, boolean ignoreCooldown) {
        if (!isEnabled() || System.currentTimeMillis() < CIRCUIT_OPEN_UNTIL) {
            return null;
        }
        if (!ignoreCooldown && !ready(botId)) {
            return null;
        }
        LAST_CALL.put(botId, System.currentTimeMillis());
        String msg = userMessage == null ? "" : userMessage.trim();
        if (msg.isEmpty()) {
            return null;
        }
        if (msg.length() > MAX_USER_CHARS) {
            msg = msg.substring(0, MAX_USER_CHARS);
        }
        Deque<String[]> history = HISTORY.computeIfAbsent(botId, k -> new ArrayDeque<>());
        try {
            String reply = request(bot.getName(), playerName, mapName, msg, history);
            if (reply != null) {
                CONSECUTIVE_FAILURES.set(0);
                append(history, "assistant", reply);
            } else {
                onApiFailure("empty reply");
            }
            return reply;
        } catch (Exception e) {
            onApiFailure(e.getMessage());
            System.out.println("[BotLLMService] 调用失败，回落本地台词: " + e.getMessage());
            return null;
        }
    }

    // SM NOTE: 连续失败达到阈值后熔断——API 不可用时每句话都要白等一次请求超时，
    // 熔断期间 ready() 直接返回 false，走本地台词，10 分钟后半开重试一次。
    private static void onApiFailure(String reason) {
        if (CONSECUTIVE_FAILURES.incrementAndGet() >= CIRCUIT_FAILURE_THRESHOLD) {
            CIRCUIT_OPEN_UNTIL = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;
            CONSECUTIVE_FAILURES.set(0);
            System.out.println("[BotLLMService] 连续失败，熔断 " + (CIRCUIT_COOLDOWN_MS / 60000)
                    + " 分钟（原因: " + reason + "），期间使用本地台词");
        }
    }

    // SM NOTE: 一次性氛围台词（击杀/受击/交易收尾等事件反应）：不写会话记忆、不依赖
    // 对话上下文，instruction 是给模型的舞台指示。失败/未启用返回 null，调用方回落本地台词池。
    public static String flavorReply(int botId, Character bot, String playerName, String mapName, String instruction) {
        if (!isEnabled() || System.currentTimeMillis() < CIRCUIT_OPEN_UNTIL) {
            return null;
        }
        LAST_CALL.put(botId, System.currentTimeMillis());
        try {
            String url = BotConfigFile.getString("llm_api_url", DEFAULT_API_URL);
            String model = BotConfigFile.getString("llm_model", DEFAULT_MODEL);
            String apiKey = BotConfigFile.getString("llm_api_key", DEFAULT_API_KEY);
            int timeoutMs = BotConfigFile.getInt("llm_timeout_ms", 5000);

            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0.9);
            root.put("max_tokens", 100);
            ArrayNode messages = root.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt(bot.getName(), playerName, mapName));
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", instruction);

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                onApiFailure("HTTP " + resp.statusCode());
                return null;
            }
            String content = MAPPER.readTree(resp.body()).path("choices").path(0)
                    .path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                onApiFailure("empty flavor reply");
                return null;
            }
            content = sanitize(content);
            if (content.isEmpty()) {
                return null;
            }
            CONSECUTIVE_FAILURES.set(0);
            return content.length() > MAX_REPLY_CHARS ? content.substring(0, MAX_REPLY_CHARS) : content;
        } catch (Exception e) {
            onApiFailure(e.getMessage());
            return null;
        }
    }

    private static String request(String botName, String playerName, String mapName, String userMessage, Deque<String[]> history)
            throws Exception {
        String url = BotConfigFile.getString("llm_api_url", DEFAULT_API_URL);
        String model = BotConfigFile.getString("llm_model", DEFAULT_MODEL);
        String apiKey = BotConfigFile.getString("llm_api_key", DEFAULT_API_KEY);
        int timeoutMs = BotConfigFile.getInt("llm_timeout_ms", 5000);

        // 先把玩家这条消息记进历史（先加后发：请求失败时历史里最多多一条没被回应的话，无害）
        append(history, "user", userMessage);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", 0.8);
        root.put("max_tokens", 200);
        ArrayNode messages = root.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt(botName, playerName, mapName));
        synchronized (history) {
            for (String[] m : history) {
                ObjectNode n = messages.addObject();
                n.put("role", m[0]);
                n.put("content", m[1]);
            }
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)))
                .build();
        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            System.out.println("[BotLLMService] 请求超时（" + timeoutMs + "ms，模型: " + model + "），本次不回复");
            throw e;
        }
        if (resp.statusCode() != 200) {
            System.out.println("[BotLLMService] HTTP " + resp.statusCode() + "（耗时 "
                    + (System.currentTimeMillis() - t0) + "ms）: " + truncate(resp.body(), 200));
            return null;
        }
        JsonNode choices = MAPPER.readTree(resp.body()).path("choices");
        String content = choices.path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }
        content = sanitize(content);
        if (content.isEmpty()) {
            return null;
        }
        if (content.length() > MAX_REPLY_CHARS) {
            content = content.substring(0, MAX_REPLY_CHARS);
        }
        return content;
    }

    // SM NOTE: LLM 输出是任意文本，但聊天包走 GBK 编码、老客户端渲染脆弱：
    // 换行/回车/控制符会让聊天气泡与聊天框排版错乱甚至假死黑屏，emoji（代理对）
    // 无法编码进 GBK。这里只保留中文/英文/常用标点与空白，其余一律剔除，
    // 连续空白压成一个空格——宁可少几个字符，不能让客户端崩。
    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWhitespace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (java.lang.Character.isHighSurrogate(c) || java.lang.Character.isLowSurrogate(c)) {
                continue; // emoji/生僻字代理对，GBK 编不出，直接丢弃
            }
            if (c == '\n' || c == '\r' || c == '\t' || java.lang.Character.getType(c) == java.lang.Character.CONTROL) {
                continue;
            }
            if (java.lang.Character.isWhitespace(c)) {
                if (!lastWhitespace) {
                    sb.append(' ');
                    lastWhitespace = true;
                }
                continue;
            }
            lastWhitespace = false;
            if (!GBK_OK[c]) {
                continue; // GBK 编不出的字符（emoji、生僻符号等），服务端直接剔除
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // 追加一条历史并裁剪到 HISTORY_LIMIT
    private static void append(Deque<String[]> history, String role, String content) {
        synchronized (history) {
            history.addLast(new String[]{role, content});
            while (history.size() > HISTORY_LIMIT) {
                history.pollFirst();
            }
        }
    }

    private static String systemPrompt(String botName, String playerName, String mapName) {
        return "你是《冒险岛》(MapleStory) 私服里的一名玩家角色，名字叫" + botName + "，"
                + "现在在地图「" + mapName + "」和玩家" + playerName + "聊天。"
                + "用简体中文口语回复，保持冒险岛玩家的口吻（打怪、练级、装备、金币、组队等话题），"
                + "回复只有一两句话、不超过40个字，不要用表情符号，不要提自己是AI或模型。";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
