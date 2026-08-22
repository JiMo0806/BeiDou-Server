package soloMapling.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * bot 数量的独立持久化配置文件。
 *
 * 在服务器工作目录（即 wz/ 所在目录；NAS 容器部署时就是挂载的持久目录 /opt/server）
 * 放置 bot-config.properties 即可覆盖数据库 game_config 的取值，改完重启服务端生效，
 * 无需进 GM 后台、无需改数据库。
 *
 * 支持的配置项（未配置或文件不存在时回落到 game_config 数据库值）：
 *   bot_population_scale  全局 bot 人口系数（0.35 ≈ 全服 1000 只；0.05 ≈ 150 只；1.0 = 完整密度约 2500 只）
 *   channel_capacity      单频道在线人数上限（世界容量 = 频道数 × channel_capacity，bot 也计入在线人数；
 *                         容量打满会导致真实玩家登录时报"所选择的游戏区已经人满"）
 */
public final class BotConfigFile {
    public static final String FILE_NAME = "bot-config.properties";

    private static final Properties PROPS = load();

    private BotConfigFile() {
    }

    private static Properties load() {
        Properties p = new Properties();
        Path file = Path.of(FILE_NAME);
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                System.out.println("[BotConfigFile] 已加载 " + file.toAbsolutePath());
            } catch (IOException e) {
                System.out.println("[BotConfigFile] 读取 " + FILE_NAME + " 失败，忽略该文件: " + e.getMessage());
            }
        }
        return p;
    }

    public static double getDouble(String key, double defaultVal) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultVal;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            System.out.println("[BotConfigFile] " + key + " 不是有效数字: " + v + "，使用默认值 " + defaultVal);
            return defaultVal;
        }
    }

    public static int getInt(String key, int defaultVal) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            System.out.println("[BotConfigFile] " + key + " 不是有效整数: " + v + "，使用默认值 " + defaultVal);
            return defaultVal;
        }
    }

    public static String getString(String key, String defaultVal) {
        String v = PROPS.getProperty(key);
        return (v == null || v.isBlank()) ? defaultVal : v.trim();
    }

    /**
     * 首次启动时在服务器工作目录生成带注释的模板文件，方便直接在 NAS 持久目录里修改。
     * 文件已存在时不覆盖。
     */
    public static void createTemplateIfAbsent() {
        Path file = Path.of(FILE_NAME);
        if (Files.exists(file)) {
            return;
        }
        try (OutputStream out = Files.newOutputStream(file);
             PrintStream ps = new PrintStream(out, false, StandardCharsets.UTF_8)) {
            ps.println("# SoloMapling bot 配置（修改后重启服务端生效）");
            ps.println("# 文件位于服务器工作目录（与 wz/ 同级），NAS 部署时即容器挂载的持久目录。");
            ps.println();
            ps.println("# 全局 bot 人口系数：0.35 ≈ 全服 1000 只 bot；0.05 ≈ 150 只；1.0 = 完整密度约 2500 只。");
            ps.println("# 未配置（保持注释）时使用数据库 game_config 的值。");
            ps.println("bot_population_scale=0.35");
            ps.println();
            ps.println("# 单频道在线人数上限。世界容量 = 频道数 × channel_capacity，bot 也计入在线人数，");
            ps.println("# 容量打满会导致真实玩家登录报\"所选择的游戏区已经人满\"（数据库默认仅 100）。");
            ps.println("# 未配置（保持注释）时使用数据库 game_config 的值。");
            ps.println("#channel_capacity=2000");
            ps.println();
            ps.println("# ── bot 聊天大模型（LLM）接入 ──────────────────────────────");
            ps.println("# llm_enabled=true 时，玩家与 bot 聊天中未命中功能关键词的自由消息会发给大模型生成回复；");
            ps.println("# 接口故障/超时会自动回落到本地随机台词，不影响游戏。修改后需重启服务端生效。");
            ps.println("llm_enabled=true");
            ps.println("# OpenAI 兼容的 chat/completions 接口地址（想换模型服务商只改这里）。");
            ps.println("llm_api_url=https://integrate.api.nvidia.com/v1/chat/completions");
            ps.println("# 模型名称。");
            ps.println("llm_model=minimaxai/minimax-m3");
            ps.println("# API Key。");
            ps.println("llm_api_key=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");
            ps.println("# 单次请求超时（毫秒），默认 8000。");
            ps.println("#llm_timeout_ms=8000");
            System.out.println("[BotConfigFile] 已生成配置模板 " + file.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[BotConfigFile] 生成模板失败（不影响启动）: " + e.getMessage());
        }
    }
}
