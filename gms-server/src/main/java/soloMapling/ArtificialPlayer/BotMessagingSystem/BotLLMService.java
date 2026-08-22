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

/**
 * bot 聊天的大模型（LLM）接入：OpenAI 兼容的 chat/completions 接口。
 *
 * 配置全部在服务器工作目录的 bot-config.properties（NAS 部署即挂载的持久目录）：
 *   llm_enabled     开关（false 或接口故障时回落本地台词）
 *   llm_api_url     接口地址，换模型服务商只改这里
 *   llm_model       模型名称
 *   llm_api_key     API Key
 *   llm_timeout_ms  单次请求超时（毫秒），默认 8000
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

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // botId -> 最近对话（role/content 交替），仅在 bot 的虚拟线程上读写自己的条目
    private static final Map<Integer, Deque<String[]>> HISTORY = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> LAST_CALL = new ConcurrentHashMap<>();

    private BotLLMService() {
    }

    public static boolean isEnabled() {
        return "true".equalsIgnoreCase(BotConfigFile.getString("llm_enabled", "false"));
    }

    // 冷却窗口外才允许发起下一次调用（调用前先占位，防止同一 bot 并发连发）
    public static boolean ready(int botId) {
        if (!isEnabled()) {
            return false;
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
        if (!ready(botId)) {
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
                append(history, "assistant", reply);
            }
            return reply;
        } catch (Exception e) {
            System.out.println("[BotLLMService] 调用失败，回落本地台词: " + e.getMessage());
            return null;
        }
    }

    private static String request(String botName, String playerName, String mapName, String userMessage, Deque<String[]> history)
            throws Exception {
        String url = BotConfigFile.getString("llm_api_url", DEFAULT_API_URL);
        String model = BotConfigFile.getString("llm_model", DEFAULT_MODEL);
        String apiKey = BotConfigFile.getString("llm_api_key", DEFAULT_API_KEY);
        int timeoutMs = BotConfigFile.getInt("llm_timeout_ms", 8000);

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
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            System.out.println("[BotLLMService] HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 200));
            return null;
        }
        JsonNode choices = MAPPER.readTree(resp.body()).path("choices");
        String content = choices.path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return null;
        }
        content = content.trim();
        if (content.length() > MAX_REPLY_CHARS) {
            content = content.substring(0, MAX_REPLY_CHARS);
        }
        return content;
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
