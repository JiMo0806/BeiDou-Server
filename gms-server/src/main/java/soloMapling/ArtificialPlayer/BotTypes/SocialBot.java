package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotChatterSystem.BotChatter;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotFlavorSystem.BotFlavor;
import soloMapling.ArtificialPlayer.BotFlavorSystem.LevelUpCongrats;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotLLMService;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTownSystem.TownStation;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTiming;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.server.MethodScheduler;
import org.gms.util.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.*;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotCustomization.getRandomChairId;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.*;
import static soloMapling.BotLogger.log;
import static soloMapling.server.SoloMaplingUtilities.random;

public class SocialBot extends BotSM {

    public enum SocialBotVariant { SINGLE_RESPONSE, INTERACTIVE }

    private enum InteractionLevel { NORMAL, REDUCED, NONVERBAL, IGNORE }

    private enum SocialBotState { IDLE_AMBIENT, GREETING, AWAITING_CHOICE, RESPONDING }

    // Written from Dispatcher/chain virtual threads, read on the tick — keep volatile.
    private volatile SocialBotState socialState = SocialBotState.IDLE_AMBIENT;
    private final SocialBotVariant variant;
    private final Map<Integer, InteractionTracker> interactionTrackers = new ConcurrentHashMap<>();

    private static final long CONVERSATION_TIMEOUT_MS = 35_000;
    private volatile long lastRespondantMessageTime = 0;

    private static final long TRACKER_CLEANUP_INTERVAL_MS = 120_000;
    private long lastCleanupTime = System.currentTimeMillis();

    // SM NOTE: hint 包防抖——每次 LLM/脚本回复后都会发交互菜单 hint 包，玩家连发几句话
    // 会在几秒内堆叠多个 hint+enableActions 包，老客户端处理不过来会黑屏卡死。
    // 同一玩家 5 秒内只重发一次。
    private static final long HINT_DEBOUNCE_MS = 5000;
    private volatile long lastHintAtMs = 0;
    private volatile int lastHintPlayerId = 0;

    private static final double RARE_LINE_CHANCE = 0.01;
    private static final double GOODBYE_SIT_CHANCE = 0.40;
    private volatile boolean wasSittingBeforeInteraction = false;
    private volatile int originalChairId = 0;

    // Stationed loiter (Feature B): claim a ledge on arrival so this cohort coordinates with returning
    // training bots' TownLoiter through the shared BotSpotClaims registry (Gap #3), and drift occasionally.
    private volatile boolean townClaimed = false;
    private volatile Point townAnchor = null; // spawn portal - the reference point relocation samples around
    private volatile boolean relocating = false; // owns movement during a drift walk; blocks ambient actions
    private volatile long nextChairActionMs = 0;
    private volatile long nextRelocateAtMs = 0;

    // Loiter tuning (candidates for a live !env chatter/loiter readout). Chair sit/stand is rare so it reads
    // as a real person resting, not a metronome; relocation is a slow per-bot drift, biased to happen while
    // unobserved so players just find bots in fresh spots.
    private static final double IDLE_CHAIR_CHANCE = 0.04;      // per eligible observed tick
    private static final long IDLE_CHAIR_COOLDOWN_MIN_MS = 60_000;
    private static final long IDLE_CHAIR_COOLDOWN_MAX_MS = 180_000;
    private static final long RELOCATE_MIN_MS = 180_000;       // 3 min
    private static final long RELOCATE_MAX_MS = 480_000;       // 8 min
    private static final double OBSERVED_RELOCATE_CHANCE = 0.15; // usually defer a drift while watched
    private static final long OBSERVED_DEFER_MS = 30_000;      // retry window when we defer an observed drift

    private static final String[] INTERACTIVE_OPTIONS = {
            "What's up?",
            "Anything interesting?",
            "Any rumors?",
            "Wanna team up?",
            "Goodbye"
    };

    private static final String DIALOGUE_PATH = "SocialBotDialogue.yaml";
    private static final String BOT_TYPE_KEY = "SocialBot";

    public SocialBot(Character character) {
        super(character);
        dialoguePath = DIALOGUE_PATH;
        botType = "SocialBot";
        this.variant = random.nextDouble() < 0.30 ? SocialBotVariant.INTERACTIVE : SocialBotVariant.SINGLE_RESPONSE;
        EventBus.getInstance().subscribe(EventType.LEVEL_UP, this); // congratulate nearby levelers
    }

    public SocialBotVariant getVariant() {
        return variant;
    }

    @Override
    public boolean isAvailableForAmbientActions() {
        // Single source of truth for every ambient system. isInConversation covers the scripted
        // multi-bot cluster convos (ConversationManager); folding it in here stops BotChatter (which
        // gates only on availability) from grabbing a bot mid cluster-conversation -> double bubbles.
        // TRADING: mid-trade the bot's voice belongs to the trade window - random ambient chatter
        // lines over the map bubble while a player haggles read as nonsense, so mute it all.
        return getState() != BotState.TRADING
                && !hasActiveRespondant() && !relocating && !BotChatter.isEngaged(getChr())
                && !ConversationManager.getInstance().isInConversation(getChr().getId());
    }

    public boolean hasActiveRespondant() {
        return getInteractors().getRespondant() != null;
    }

    @Override
    public void checkPrioritySpeed() {
        // Snappy while a player is mid-conversation, and also while the bot is armed after saying yes -
        // so pollRecruitInvite drains the incoming invite promptly instead of dropping to slow cadence
        // once resetConversation ends the exchange.
        if (hasActiveRespondant() || BotRecruitManager.isArmed(getChr().getId())) {
            setPriorityHigh();
            return;
        }
        if (checkMainPlayersOnMap()) {
            setPriorityNormal();
            return;
        }
        updateScheduleDelay(30000);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) return;
        getDebugger().debugLoggingFull(String.format("%s SocialBotState: %s", getChr().getName(), socialState), String.format("%s", socialState));

        checkPrioritySpeed();

        if (hasActiveRespondant()) {
            checkConversationTimeout();
            processMessages();
        }

        cleanupExpiredTrackers();
        processQueuedEvents(); // drain any LEVEL_UP congrats events buffered since the last tick
        if (isAvailableForAmbientActions()) {
            ensureTownClaim(); // claim this bot's ledge once (coordinates with returning training-bot loiter)
            BotFlavor.maybeExpress(this); // occasional idle emote / buff-flex / skill-swing (self-gated)
            BotChatter.maybeStartChatter(this); // occasional short back-and-forth with a nearby town bot
            maybeIdleChair(); // occasional sit/stand while idle - a resting townsperson
            maybeRelocate(); // last: rare drift to a fresh anchor-weighted spot (may block the tick's walk)
        }
        pollRecruitInvite(); // last: a JOINED poll converts this bot to a FollowerBot
    }

    @Override
    public void handleEvent(GameEvent event) {
        // A nearby character (player or bot) levelled up - maybe congratulate them.
        LevelUpCongrats.react(this, event);
    }

    // Best-effort ambient cleanup on teardown / convert. The chatter engaged-registry self-expires, so
    // forget() is belt-and-suspenders; releasing the ledge claim frees a slot for other town bots.
    @Override
    public synchronized void stopScheduledTask() {
        Character chr = getChr();
        BotChatter.forget(chr);
        TownStation.releaseSpot(chr);
        super.stopScheduledTask();
    }

    // Claim this bot's ledge once, on its first available tick. The sampler already placed it well, so this
    // is claim-only (no move); the shared BotSpotClaims cap then keeps a returning training-bot crowd from
    // stacking on a ledge a stationed bot already holds. Stores the town anchor (spawn portal) for drift.
    private void ensureTownClaim() {
        if (townClaimed) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        townAnchor = resolveTownAnchor(chr);
        TownStation.claimSpot(chr);
        // Desync: seed the first chair + relocation timers with a random offset so a freshly-spawned cohort
        // (or a crowd reacting to a player's arrival) doesn't sit / drift in lockstep.
        long now = System.currentTimeMillis();
        nextChairActionMs = now + (long) (random.nextDouble() * IDLE_CHAIR_COOLDOWN_MAX_MS);
        nextRelocateAtMs = now + RELOCATE_MIN_MS + (long) (random.nextDouble() * (RELOCATE_MAX_MS - RELOCATE_MIN_MS));
        townClaimed = true;
    }

    // Occasionally sit (then later stand) while idle - cheap, already-proven packets, no engine conflict.
    // Gated on a real observer (it's packets) and paced by a per-bot cooldown so it isn't a metronome.
    private void maybeIdleChair() {
        if (!isAvailableForAmbientActions()) {
            return; // an earlier tick step (e.g. chatter) may have engaged this bot
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextChairActionMs || random.nextDouble() >= IDLE_CHAIR_CHANCE) {
            return; // not yet, or a quiet tick (timer only advances when an action actually fires)
        }
        if (chr.getChair() > 0) {
            botCancelChair(chr);
        } else {
            botSitChair(chr, getRandomChairId());
        }
        nextChairActionMs = now + IDLE_CHAIR_COOLDOWN_MIN_MS
                + (long) (random.nextDouble() * (IDLE_CHAIR_COOLDOWN_MAX_MS - IDLE_CHAIR_COOLDOWN_MIN_MS));
    }

    // Rare drift to a fresh anchor-weighted spot ("stand near the potion shop a while, then wander to the
    // smithy"). Preferably fires while unobserved (bots just appear in new spots); a watched stroll is
    // allowed but rare. The walk is a BLOCKING old-engine pathfind, run synchronously on the tick as
    // deliberate choreography (the bot is intentionally inert while it strolls; relocating gates it out of
    // other ambient actions and partner selection).
    private void maybeRelocate() {
        if (!isAvailableForAmbientActions()) {
            return; // don't walk a bot that a chatter chain just engaged this tick
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRelocateAtMs) {
            return;
        }
        if (GCMovement.isMapObserved(chr.getMapId()) && random.nextDouble() >= OBSERVED_RELOCATE_CHANCE) {
            nextRelocateAtMs = now + OBSERVED_DEFER_MS; // defer: prefer to drift while nobody's watching
            return;
        }
        relocating = true;
        try {
            if (chr.getChair() > 0) {
                botCancelChair(chr); // can't stroll from a chair
            }
            TownStation.relocate(chr, townAnchor);
        } finally {
            relocating = false;
            nextRelocateAtMs = System.currentTimeMillis() + RELOCATE_MIN_MS
                    + (long) (random.nextDouble() * (RELOCATE_MAX_MS - RELOCATE_MIN_MS));
        }
    }

    private Point resolveTownAnchor(Character chr) {
        try {
            if (chr.getMap() != null && chr.getMap().getPortal(0) != null) {
                return chr.getMap().getPortal(0).getPosition();
            }
        } catch (Exception ignored) {
            // fall back to current position
        }
        return chr.getPosition();
    }

    @Override
    protected void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance()
                    .getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) return;

            if (isBot(message.getSender())) return;

            Character respondant = getInteractors().getRespondant();
            if (respondant == null || message.getSender().getId() != respondant.getId()) return;

            lastRespondantMessageTime = System.currentTimeMillis();
            handlePlayerMessage(message);
        } catch (Exception e) {
            log("[SocialBot] processMessages error: " + e.getMessage());
        }
    }

    public void onFirstInteraction(Character player) {
        lastRespondantMessageTime = System.currentTimeMillis();

        InteractionTracker tracker = getOrCreateTracker(player.getId());
        InteractionLevel level = tracker.getLevel();

        if (level == InteractionLevel.IGNORE) {
            showBusyHint(player);
            resetConversation();
            return;
        }

        tracker.increment();
        wasSittingBeforeInteraction = getChr().getChair() > 0;
        originalChairId = wasSittingBeforeInteraction ? getChr().getChair() : 0;

        // Scripted beats ride a BotTiming chain; the gate silently drops the
        // rest of the script if the conversation resets or changes hands mid-way.
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player));
        if (wasSittingBeforeInteraction) {
            chain.run(() -> botCancelChair(getChr())).pause(600);
        }
        chain.run(() -> botFaceTowardsPoint(getChr(), player.getPosition()))
                .pauseRandom(2000, 4000);

        switch (level) {
            case NORMAL:
                if (variant == SocialBotVariant.SINGLE_RESPONSE) {
                    appendSingleResponse(chain, player);
                } else {
                    // SM NOTE: 点名后立即可接话——打招呼链开头有 2-4 秒的"人性化"pause（玩家一
                    // 点完名就会立刻打字），而 AWAITING_CHOICE 原本要等链播完才设置，玩家点完名
                    // 马上发来的第一条文字会命中 handlePlayerMessage 的 AWAITING_CHOICE 判断，
                    // 状态还是 IDLE_AMBIENT → 消息被吞，bot 完全不理（这就是"发文字没理我"）。
                    // 这里在启动链之前同步先置为 AWAITING_CHOICE，玩家的第一句话就能正常接住；
                    // 链末尾原有的重复置位留着无害，打招呼的节拍不受影响。
                    socialState = SocialBotState.AWAITING_CHOICE;
                    appendGreeting(chain, player);
                }
                break;
            case REDUCED:
                chain.run(() -> doReducedResponse(player))
                        .run(this::resetConversation);
                break;
            case NONVERBAL:
                chain.run(() -> doNonverbalResponse(player))
                        .run(this::resetConversation);
                break;
        }
        chain.start();
    }

    private void handlePlayerMessage(ChatMessage message) {
        Character player = message.getSender();
        InteractionTracker tracker = getOrCreateTracker(player.getId());
        InteractionLevel level = tracker.getLevel();

        if (level == InteractionLevel.IGNORE) {
            showBusyHint(player);
            resetConversation();
            return;
        }
        if (level == InteractionLevel.NONVERBAL) {
            doNonverbalResponse(player);
            tracker.increment();
            resetConversation();
            return;
        }
        if (level == InteractionLevel.REDUCED) {
            doReducedResponse(player);
            tracker.increment();
            resetConversation();
            return;
        }

        if (socialState == SocialBotState.AWAITING_CHOICE) {
            handleDialogueChoice(message.getContent(), player);
            tracker.increment();
        }
    }

    private void handleDialogueChoice(String content, Character player) {
        String lower = content.trim().toLowerCase();

        String category = null;
        if (lower.equals("1") || lower.contains("what's up") || lower.contains("whats up")) {
            category = "WhatsUp";
        } else if (lower.equals("2") || lower.contains("interesting")) {
            category = "Interesting";
        } else if (lower.equals("3") || lower.contains("rumor")) {
            category = "Rumors";
        } else if (lower.equals("4") || lower.contains("team") || lower.contains("party")) {
            handlePartyAsk(player);
            return;
        } else if (lower.equals("5") || lower.contains("goodbye") || lower.contains("bye") || lower.contains("cya")) {
            doGoodbye(player);
            resetConversation();
            return;
        }

        if (category == null) {
            // 自由聊天：没命中任何功能关键词时先问大模型（bot-config.properties 的 llm_* 配置，
            // 异步调用不阻塞 tick 线程），不可用/失败则回落本地随机台词。
            if (tryLlmReply(content, player)) {
                return;
            }
            category = "WhatsUp";
        }

        if (random.nextDouble() < RARE_LINE_CHANCE) {
            category = "Rare";
        }

        String line = getRandomLine(category, player);
        int emote = getRandomEmote(category);
        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player))
                .pauseRandom(2000, 4000)
                .run(() -> botFaceTowardsPoint(getChr(), player.getPosition()));
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        chain.run(() -> showInteractiveOptions(player));
        chain.start();
    }

    // --- Party recruiting ---

    // "Wanna team up?": roll accept/decline via the shared recruit brain. Accept arms a 60s window
    // for THIS player's party invite (pollRecruitInvite answers it) and ends the conversation;
    // decline speaks a flavored excuse and loops back to the options like any other category.
    private void handlePartyAsk(Character player) {
        BotRecruitManager.RecruitAnswer ans = getChr().getParty() != null
                ? BotRecruitManager.RecruitAnswer.DECLINED // already committed to a party
                : BotRecruitManager.rollPartyAsk(getChr(), player, BotRecruitManager.SOCIAL_ACCEPT_CHANCE, true);

        boolean accepted = ans == BotRecruitManager.RecruitAnswer.ACCEPTED;
        String category = accepted ? "PartyAccept" : "PartyDecline";
        String line = getRandomLine(category, player);
        int emote = getRandomEmote(category);

        BotTiming.Chain chain = BotTiming.chain()
                .stopUnless(() -> isConversationWith(player))
                .pauseRandom(1500, 3000)
                .run(() -> botFaceTowardsPoint(getChr(), player.getPosition()));
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        if (accepted) {
            chain.run(this::resetConversation); // hint clears; the tick poll now waits for the invite
        } else {
            chain.run(() -> showInteractiveOptions(player));
        }
        chain.start();
    }

    // Answers any pending party invite every tick: the armed recruiter's invite is accepted and the
    // bot converts itself into a FollowerBot (party membership rides the Character through the
    // conversion); anything unsolicited gets a polite decline so the first-wins queue never rots.
    private void pollRecruitInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        int recruiterId = BotRecruitManager.armedInviterId(chr.getId()); // read BEFORE poll - JOINED clears it
        BotRecruitManager.InvitePoll res = BotRecruitManager.pollInvites(chr);
        if (res != BotRecruitManager.InvitePoll.JOINED) {
            return;
        }
        Character recruiter = chr.getClient().getChannelServer().getPlayerStorage().getCharacterById(recruiterId);
        String line = getRandomLine("PartyJoined", recruiter);
        if (line != null) {
            BotSpeak(chr, line);
        }
        // SM NOTE: a quick wave hello to the leader after joining - reads like a real player greeting
        BotTiming.afterRandom(800, 2000, () -> BotEmote(chr, 7));
        BotRecruitManager.setPendingLeader(chr.getId(), recruiterId);
        BotTypeManager.convertBotType(chr, BotTypeManager.BotType.FOLLOWER_BOT);
    }

    // --- LLM free chat ---

    // 把没命中功能关键词的自由消息交给大模型（llm_enabled=true 且不在冷却时）。HTTP 调用
    // 放进独立虚拟线程，成功后延迟一两秒"打字回复"；拿不到回复就同步回落 WhatsUp 台词，
    // 玩家侧感知不到接口故障。
    private boolean tryLlmReply(String content, Character player) {
        Character chr = getChr();
        if (!BotLLMService.ready(chr.getId())) {
            return false;
        }
        String playerName = player.getName();
        String mapName = chr.getMap().getMapName();
        Thread.ofVirtual().name("bot-llm-" + chr.getId()).start(() -> {
            String raw;
            try {
                raw = BotLLMService.chat(chr.getId(), chr, playerName, mapName, content);
            } catch (Exception e) {
                raw = null;
            }
            if (raw == null) {
                // 回落本地台词（和 WhatsUp 分支同款节拍）
                String line = getRandomLine("WhatsUp", player);
                int emote = getRandomEmote("WhatsUp");
                if (line != null) {
                    BotSpeak(chr, line);
                }
                if (emote > 0) {
                    BotEmote(chr, emote);
                }
                showInteractiveOptions(player);
                return;
            }
            final String reply = raw;
            BotTiming.Chain chain = BotTiming.chain()
                    .stopUnless(() -> isConversationWith(player))
                    .pauseRandom(1500, 3000)
                    .run(() -> botFaceTowardsPoint(chr, player.getPosition()))
                    .run(() -> BotSpeak(chr, reply))
                    .run(() -> showInteractiveOptions(player));
            chain.start();
        });
        return true;
    }

    // --- Response types ---
    // Lines/emotes are picked at chain-build time (a few seconds before they play);
    // no visible difference, and it keeps the chain steps to pure packet sends.

    private void appendSingleResponse(BotTiming.Chain chain, Character player) {
        String category = random.nextDouble() < RARE_LINE_CHANCE ? "Rare" : "SingleResponse";
        String line = getRandomLine(category, player);
        int emote = getRandomEmote(category);
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line))
                    .pauseRandom(500, 1000);
            if (emote > 0) {
                chain.run(() -> BotEmote(getChr(), emote));
            }
        }
        chain.run(() -> botFaceTowardsPoint(getChr(), player.getPosition()));
        appendResit(chain);
        chain.run(this::resetConversation);
    }

    private void appendGreeting(BotTiming.Chain chain, Character player) {
        String line = getRandomLine("Greeting", player);
        int emote = getRandomEmote("Greeting");
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        chain.run(() -> {
            showInteractiveOptions(player);
            socialState = SocialBotState.AWAITING_CHOICE;
        });
    }

    // Farewell beats play on their own chain so the tick isn't blocked; the
    // conversation resets immediately after. The gate tolerates that reset
    // (respondant null) but drops the tail if a NEW conversation starts.
    private void doGoodbye(Character player) {
        String line = getRandomLine("Goodbye", player);
        int emote = getRandomEmote("Goodbye");
        BotTiming.Chain chain = BotTiming.chain().stopUnless(() -> {
            Character r = getInteractors().getRespondant();
            return r == null || r.getId() == player.getId();
        });
        if (line != null) {
            chain.run(() -> BotSpeak(getChr(), line));
            if (emote > 0) {
                chain.pause(400).run(() -> BotEmote(getChr(), emote));
            }
        }
        appendResit(chain);
        chain.start();
    }

    private void doReducedResponse(Character player) {
        String line = getRandomLine("Reduced", player);
        if (line != null) {
            BotSpeak(getChr(), line);
        }
    }

    private void doNonverbalResponse(Character player) {
        String line = getRandomLine("Nonverbal", player);
        if (line != null) {
            BotSpeak(getChr(), line);
        }
    }

    // Decides the re-sit at build time; the sit itself lands as a later beat.
    private void appendResit(BotTiming.Chain chain) {
        boolean resit = wasSittingBeforeInteraction || random.nextDouble() < GOODBYE_SIT_CHANCE;
        int chairId = originalChairId > 0 ? originalChairId : getRandomChairId();
        wasSittingBeforeInteraction = false;
        originalChairId = 0;
        if (resit) {
            chain.pauseRandom(1000, 2500).run(() -> botSitChair(getChr(), chairId));
        }
    }

    private boolean isConversationWith(Character player) {
        Character r = getInteractors().getRespondant();
        return r != null && r.getId() == player.getId();
    }

    private void showBusyHint(Character player) {
        player.yellowMessage("They seem busy...");
        player.getClient().sendPacket(PacketCreator.enableActions());
        MethodScheduler.runAfterDelay(() -> expirePlayerChatCommands(player), 5000);
    }

    // SM NOTE: 点名对话黑屏根因（第三轮）——PLAYER_HINT 弹窗菜单包本身：点名后 bot 会弹
    // 选项气泡（1. What's up? 等），玩家一发文字，老客户端在"输入中 + 菜单悬停"状态下撞到
    // hint 包就黑屏卡死（前两轮清的是清除包/防抖，只能降低频率，弹窗还在就会崩）。
    // 彻底移除弹窗：改为 bot 用普通聊天说一句引导语（聊天包绝对安全），自由聊天走 LLM；
    // 玩家回数字/关键词的匹配逻辑（handleDialogueChoice）原样保留。引导语只念一次。
    private void showInteractiveOptions(Character player) {
        if (player == null || player.getClient() == null) {
            return;
        }
        boolean firstTime = lastHintPlayerId != player.getId();
        long now = System.currentTimeMillis();
        if (!firstTime && now - lastHintAtMs < HINT_DEBOUNCE_MS) {
            return; // 防抖：同一玩家短时间内别再重复念引导语
        }
        lastHintPlayerId = player.getId();
        lastHintAtMs = now;
        if (firstTime) {
            BotTiming.afterRandom(300, 700, () -> BotSpeak(getChr(), "想聊点什么？直接说话就行，组队喊我或者告别也都行！"));
        }
    }

    // --- Timeout ---

    private void checkConversationTimeout() {
        if (!hasActiveRespondant()) return;
        if (lastRespondantMessageTime == 0) return;

        long elapsed = System.currentTimeMillis() - lastRespondantMessageTime;
        if (elapsed > CONVERSATION_TIMEOUT_MS) {
            String line = getRandomLine("Timeout", getInteractors().getRespondant());
            if (line != null) {
                BotSpeak(getChr(), line);
            }
            resetConversation();
        }
    }

    private void resetConversation() {
        Character respondant = getInteractors().getRespondant();
        if (respondant != null) {
            expirePlayerChatCommands(respondant);
        }
        BotLLMService.clear(getChr().getId()); // 对话结束，大模型的会话记忆一并清空
        getInteractors().resetRespondant();
        socialState = SocialBotState.IDLE_AMBIENT;
        lastRespondantMessageTime = 0;
    }

    // --- Anti-spam tracker ---

    private InteractionTracker getOrCreateTracker(int playerId) {
        return interactionTrackers.computeIfAbsent(playerId, k -> new InteractionTracker());
    }

    private void cleanupExpiredTrackers() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < TRACKER_CLEANUP_INTERVAL_MS) return;
        lastCleanupTime = now;
        interactionTrackers.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    // --- Dialogue helpers ---

    // Resolves any {TOKEN}s (incl. {PLAYER_*}) against this bot and the player it's reacting to.
    // A line whose tokens can't resolve is dropped rather than spoken raw.
    private String getRandomLine(String category, Character player) {
        try {
            return BotDialogueHandler.getRandomResolvedLine(DIALOGUE_PATH, BOT_TYPE_KEY, category, getChr(), player);
        } catch (Exception e) {
            return null;
        }
    }

    private int getRandomEmote(String category) {
        try {
            BotDialogueHandler.DialogueConstructor dialog =
                    BotDialogueHandler.getDialogueCon(DIALOGUE_PATH, BOT_TYPE_KEY, category);
            if (dialog == null) return -1;
            return dialog.getEmote();
        } catch (Exception e) {
            return -1;
        }
    }

    // --- Anti-spam tracking ---

    private static class InteractionTracker {
        private int count = 0;
        private long lastInteractionTime;
        private static final long COOLDOWN_MS = 300_000;

        public InteractionTracker() {
            this.lastInteractionTime = System.currentTimeMillis();
        }

        public void increment() {
            count++;
            lastInteractionTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - lastInteractionTime > COOLDOWN_MS;
        }

        public InteractionLevel getLevel() {
            if (isExpired()) {
                reset();
                return InteractionLevel.NORMAL;
            }
            if (count <= 3) return InteractionLevel.NORMAL;
            if (count <= 4) return InteractionLevel.REDUCED;
            if (count == 5) return InteractionLevel.NONVERBAL;
            return InteractionLevel.IGNORE;
        }

        public void reset() {
            count = 0;
            lastInteractionTime = System.currentTimeMillis();
        }
    }
}
