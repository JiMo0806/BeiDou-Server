package soloMapling.ArtificialPlayer.BotFlavorSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotLLMService;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

// Event-driven emotional reactions: where BotFlavor covers IDLE expressions, this layer reacts to
// gameplay EVENTS - a kill landed, a mob hit taken, a trade finished. A real player grinning after
// a kill or yelping when a monster connects is what sells the illusion far more than idle fidget.
//
// Everything here is optional garnish on top of existing flows: hard-gated on a real player
// observing the map (no packets when nobody watches), chance + per-bot cooldown so a grinding bot
// doesn't emote every kill, and every spoken line has a hardcoded fallback so an LLM outage (or
// llm_enabled=false) changes nothing about the flow - the bot just uses the local line pool.
public final class BotReactionFlavor {

    private BotReactionFlavor() {
    }

    // ---- tuning knobs ----
    private static final double KILL_REACT_CHANCE = 0.12;      // per kill, after cooldown gate
    private static final long KILL_COOLDOWN_MIN_MS = 45_000;
    private static final long KILL_COOLDOWN_MAX_MS = 120_000;
    private static final double KILL_LINE_CHANCE = 0.30;       // of reactions, how many also speak

    private static final double HIT_REACT_CHANCE = 0.25;       // per real (non-miss) mob hit
    private static final long HIT_COOLDOWN_MIN_MS = 20_000;
    private static final long HIT_COOLDOWN_MAX_MS = 40_000;
    private static final double HIT_LINE_CHANCE = 0.35;

    private static final int[] HAPPY_EMOTES = {1, 2, 5, 6};    // F1/F2/F5/F6
    private static final int[] SAD_EMOTES = {3, 4};            // F3/F4 (ouch / frown)

    private static final String[] KILL_LINES = {
            "搞定，下一个！", "哼，不自量力", "这波手感不错", "小样儿还想跑？",
            "又升级有望了", "嘿嘿，轻松"
    };
    private static final String[] HIT_LINES = {
            "哎哟！", "疼疼疼", "这怪真凶！", "别咬我啊", "好险好险"
    };

    // Per-bot reaction cooldowns, keyed by character id (shared across threads - combat ticker,
    // movement ticker, macro tick - so concurrent).
    private static final Map<Integer, Long> KILL_COOLDOWN = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> HIT_COOLDOWN = new ConcurrentHashMap<>();

    // Drop a bot's cooldown records on teardown so the maps don't leak.
    public static void forget(Character chr) {
        if (chr != null) {
            KILL_COOLDOWN.remove(chr.getId());
            HIT_COOLDOWN.remove(chr.getId());
        }
    }

    // A kill just landed (called from the shared combat beat). Occasional happy emote, and
    // sometimes a spoken victory line - LLM first, local pool as the guaranteed fallback.
    public static void onKill(Character bot) {
        if (!observed(bot)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long until = KILL_COOLDOWN.get(bot.getId());
        if (until != null && now < until) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= KILL_REACT_CHANCE) {
            return;
        }
        armCooldown(KILL_COOLDOWN, bot.getId(), now, KILL_COOLDOWN_MIN_MS, KILL_COOLDOWN_MAX_MS);
        BotEmote(bot, HAPPY_EMOTES[ThreadLocalRandom.current().nextInt(HAPPY_EMOTES.length)]);
        if (ThreadLocalRandom.current().nextDouble() < KILL_LINE_CHANCE) {
            speakFlavor(bot,
                    "（旁白：你刚击杀了一只怪物，随口哼一句简短的胜利感言，不超过10个字）",
                    KILL_LINES);
        }
    }

    // A mob just landed a real hit on the bot (called from the contact-damage path). Occasional
    // ouch emote / yelp - a bot that never flinches reads as a punching bag.
    public static void onMobHit(Character bot) {
        if (!observed(bot)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long until = HIT_COOLDOWN.get(bot.getId());
        if (until != null && now < until) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= HIT_REACT_CHANCE) {
            return;
        }
        armCooldown(HIT_COOLDOWN, bot.getId(), now, HIT_COOLDOWN_MIN_MS, HIT_COOLDOWN_MAX_MS);
        BotEmote(bot, SAD_EMOTES[ThreadLocalRandom.current().nextInt(SAD_EMOTES.length)]);
        if (ThreadLocalRandom.current().nextDouble() < HIT_LINE_CHANCE) {
            speakFlavor(bot,
                    "（旁白：你刚被怪物撞了一下，随口喊一句简短的痛呼，不超过6个字）",
                    HIT_LINES);
        }
    }

    // Speak a one-off flavor line: LLM first (async, one-shot, no conversation-memory pollution),
    // local pool as the guaranteed fallback. Callers pass a stage-direction instruction; the
    // service's persona prompt keeps the reply in-character.
    public static void speakFlavor(Character bot, String instruction, String[] fallbackLines) {
        if (bot == null || fallbackLines == null || fallbackLines.length == 0) {
            return;
        }
        String fallback = fallbackLines[ThreadLocalRandom.current().nextInt(fallbackLines.length)];
        if (!BotLLMService.ready(bot.getId())) {
            BotSpeak(bot, fallback);
            return;
        }
        String mapName = bot.getMap() == null ? "" : bot.getMap().getMapName();
        Thread.ofVirtual().name("bot-flavor-" + bot.getId()).start(() -> {
            String line = null;
            try {
                line = BotLLMService.flavorReply(bot.getId(), bot, "路人玩家", mapName, instruction);
            } catch (Exception ignored) {
                // LLM hiccup: fall straight through to the local line
            }
            BotSpeak(bot, line != null ? line : fallback);
        });
    }

    private static boolean observed(Character bot) {
        return bot != null && bot.getMap() != null && GCMovement.isMapObserved(bot.getMapId());
    }

    private static void armCooldown(Map<Integer, Long> map, int id, long now, long minMs, long maxMs) {
        long span = Math.max(1, maxMs - minMs);
        map.put(id, now + minMs + (long) (ThreadLocalRandom.current().nextDouble() * span));
    }
}
