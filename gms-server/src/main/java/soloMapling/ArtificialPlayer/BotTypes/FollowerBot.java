package soloMapling.ArtificialPlayer.BotTypes;

import org.gms.client.Character;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotOptionMenu;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

import java.util.List;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;

// A recruited companion: stays with one real player. The heavy lifting is the GC follow
// engine (50ms same-map tailing + 400ms cross-map session that travels portal chains and redirects
// mid-trip - see GCFollow); this FSM is only the supervisor: it tracks the leader BY ID so a relog
// re-attaches (a session holds a hard Character ref and dies with it), re-arms the session whenever
// it's down, watches party membership, and converts itself away when the ride ends.
//
// In the wild the follower does NOT tail the leader: once it's on the leader's mob map it enters
// FREELANCE - the same GrindBrain a TrainingBot uses owns its movement and combat (spot claims,
// mobbing with AoE skills, loot, rope recovery) at the shared 250ms combat cadence, so party bots
// spread out and each mind their own hunt. It regroups instantly when the leader changes map, the
// party dissolves, or the grind wedges; "Come to me!" (rally) holds tight following for ~2 min.
// Towns keep the classic tight follow.
//
// Cadence: pinned fast (~750ms) and governor-exempt - a follower is foreground content whether or
// not its current map is observed (it may be catching up through empty maps). The map it shares
// with its leader is FULL by definition, so this costs almost nothing in practice.
//
// Lifecycle in: SocialBot recruit (party join -> convert), TrainingBot "Follow me!", !bot followbot.
// Lifecycle out: "Train here with me!" -> TrainingBot (station-here handoff); leader gone past the
// grace / party dissolved -> TrainingBot on a mob map, SocialBot in a town.
public class FollowerBot extends BotSM implements CombatTickable {

    private static final long FOLLOW_TICK_MS = 750;
    private static final long LEADER_LOST_GRACE_MS = 90_000;
    // A rally ("come to me / 集合") holds tight following this long before the bots disperse to
    // freelance again (only meaningful on a mob map - in a town they follow anyway).
    private static final long RALLY_HOLD_MS = 120_000;
    // Self-heal: a freelancer that lands nothing for this long (wedged / boxed in) gives up its
    // spot and regroups on the leader instead of standing frozen forever.
    private static final long FREELANCE_STUCK_MS = 90_000;
    // Min gap between "dispersing to hunt" one-liners so map-hopping leaders don't make it chatty.
    private static final long FREELANCE_SAY_GAP_MS = 60_000;
    // A new party member (or this bot's own fresh join) triggers a round of party buffs, but
    // never more often than this - a joining spree must not turn into a buff spam loop.
    private static final long JOIN_BUFF_COOLDOWN_MS = 60_000;

    private enum FollowPhase { INIT, FOLLOW, FREELANCE, LEADER_LOST }

    // Written on the macro tick, read from menu callbacks / the combat ticker - keep volatile.
    private volatile FollowPhase followPhase = FollowPhase.INIT;
    private volatile int leaderId = -1;
    private volatile long leaderLostSinceMs = 0;
    private volatile boolean wasPartied = false;
    private volatile boolean pausedForTrade = false;
    private volatile long rallyUntilMs = 0;
    private volatile long freelanceLastSayMs = 0;
    private volatile long lastJoinBuffMs = 0;
    // Party roster snapshot for the new-member buff round (parties expose no push events to
    // bots, so membership changes are diffed here on the macro tick).
    private final Set<Integer> knownMemberIds = new HashSet<>();

    // The per-bot grind engine (same one TrainingBot uses): owns spot claims, target stickiness,
    // engage cadence, loot, rope recovery. Driven at combat cadence via TrainingBot's shared ticker.
    private final GrindBrain grind = new GrindBrain(msg -> { });

    // NOTE: no "here" keyword anywhere - "there" contains "here", so a casual "hi there" would trigger
    // it (and "train here with me" would hijack the rally option if rally matched "here").
    private final BotOptionMenu menu = new BotOptionMenu(this,
            List.of("Come to me!", "Train here with me!", "Nevermind"),
            List.of(List.of("come", "gather", "rally", "regroup", "集合", "过来", "召回"),
                    List.of("train", "grind", "station"),
                    List.of("nevermind", "bye", "nah", "nope")),
            this::onMenuSelect);

    public FollowerBot(Character character) {
        super(character);
        botType = "FollowerBot";
        dialoguePath = "FollowerBotDialogue.yaml";
    }

    // Pinned cadence, observed or not: supervision (re-arm/relog/party checks) must never sleep 9-12s.
    @Override
    public void checkPrioritySpeed() {
        updateScheduleDelay(FOLLOW_TICK_MS);
    }

    @Override
    public synchronized void startScheduledTask(long initialDelayMs) {
        super.startScheduledTask(initialDelayMs);
        int id = getChr().getId();
        BotTickService.setNoThrottle(id, true);
        // Conversions inherit the spawn-choreography stagger, but a converted follower is already
        // live in-world - pull the first tick to the follow cadence so it starts moving immediately.
        BotTickService.reschedule(id, FOLLOW_TICK_MS);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        if (getState() == BotState.TRADING) {
            // Trades are sacred: halt the follow session (and any freelance grind) so the bot
            // doesn't walk out of the trade. After the trade the normal FOLLOW logic re-arms and,
            // on a mob map, disperses to freelance again.
            if (followPhase == FollowPhase.FREELANCE) {
                exitFreelance();
                followPhase = FollowPhase.FOLLOW;
            }
            if (!pausedForTrade) {
                pausedForTrade = true;
                GCMovement.stop(chr);
            }
            return;
        }
        pausedForTrade = false;

        switch (followPhase) {
            case INIT -> doInit();
            case FOLLOW -> doFollow();
            case FREELANCE -> doFreelance();
            case LEADER_LOST -> doLeaderLost();
        }

        if (!getRunning()) {
            return; // a phase converted this bot away mid-tick
        }
        pollPartyBuffRound();
        pollLeaderInvite();
        menu.poll(); // last: a selection may also convert this bot away
    }

    // New party member (or this bot's own fresh join - the whole roster is "new" on the first
    // tick after conversion) -> throw a round of party buffs a few seconds in, so newcomers
    // land with working buffs. Poll-based diff of the roster; cooldown guards joining sprees.
    private void pollPartyBuffRound() {
        Character chr = getChr();
        Party party = chr.getParty();
        if (party == null) {
            knownMemberIds.clear();
            return;
        }
        boolean hasNewMember = false;
        for (PartyCharacter pc : party.getMembers()) {
            if (pc == null) {
                continue;
            }
            if (!knownMemberIds.contains(pc.getId())) {
                hasNewMember = true;
                break;
            }
        }
        if (hasNewMember && now() - lastJoinBuffMs >= JOIN_BUFF_COOLDOWN_MS) {
            lastJoinBuffMs = now();
            BotBuffRequestHandler.schedulePartyBuffRound(chr, 2_000, 5_000);
        }
        knownMemberIds.clear();
        for (PartyCharacter pc : party.getMembers()) {
            if (pc != null) {
                knownMemberIds.add(pc.getId());
            }
        }
    }

    // An unpartied follower (e.g. via !bot followbot) accepts a party invite from its OWN leader -
    // that's how the GM flow gets party EXP going - and politely rejects anyone else so the
    // first-wins-free queue never holds a rotting entry.
    private void pollLeaderInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(chr);
        Character inviter = entry == null ? null : entry.getInviter();
        if (inviter != null && inviter.getId() == leaderId && chr.getParty() == null) {
            if (BotPartyCommands.botAcceptPartyInvite(chr)) {
                wasPartied = true;
                sayNode("PartyJoined", inviter);
            }
            return;
        }
        BotPartyCommands.botRejectPartyInvite(chr);
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        Character chr = getChr();
        int pending = BotRecruitManager.consumePendingLeader(chr.getId());
        leaderId = pending > 0 ? pending : leaderIdFromParty();
        if (leaderId <= 0) {
            fallbackConvert();
            return;
        }
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            return;
        }
        wasPartied = chr.getParty() != null;
        GCMovement.follow(chr, leader);
        sayNode("FollowStart", leader);
        followPhase = FollowPhase.FOLLOW;
    }

    private void doFollow() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            sayNode("LeaderLost", null);
            return;
        }
        if (chr.getParty() != null) {
            wasPartied = true;
        } else if (wasPartied) {
            // Kicked / disband: the ride is over.
            sayNode("PartyFarewell", leader);
            fallbackConvert();
            return;
        }
        if (!GCMovement.isFollowing(chr)) {
            // Initial arm, post-trade re-arm, and relog re-attach (a dead session cleared itself;
            // the freshly resolved leader Character makes the new session current).
            GCMovement.follow(chr, leader);
        }
        // Wild maps: each follower minds its own business. Once the bot has caught up onto the
        // leader's mob map (and no rally hold is active), it stops tailing and disperses to grind
        // on its own via its GrindBrain - the leader only regroups them with "Come to me!". In
        // towns (no mobs) or while rallying it keeps the classic tight follow.
        if (chr.getMapId() == leader.getMapId()
                && now() >= rallyUntilMs
                && MapMobIndex.level(chr.getMapId()) >= 0) {
            enterFreelance(leader);
        }
    }

    // Dispersed hunting on the leader's map: the GrindBrain owns movement + combat (driven at
    // combat cadence by TrainingBot's shared ticker via onSharedCombatTick); this macro tick only
    // supervises - regrouping the bot the moment the leader moves on, the party dissolves, or the
    // grind engine looks wedged.
    private void doFreelance() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            exitFreelance();
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            sayNode("LeaderLost", null);
            return;
        }
        if (wasPartied && chr.getParty() == null) {
            // Kicked / disband: the ride is over.
            exitFreelance();
            sayNode("PartyFarewell", leader);
            fallbackConvert();
            return;
        }
        // The leader moved on (or is in a town now): drop the spot and catch up - the follow
        // session re-arms itself on the next doFollow tick.
        if (chr.getMapId() != leader.getMapId()) {
            exitFreelance();
            followPhase = FollowPhase.FOLLOW;
            return;
        }
        // Self-heal: wedged / dead map -> stop hunting alone and regroup on the leader.
        if (grind.msSinceProgress() > FREELANCE_STUCK_MS
                && GCMovement.isMapObserved(chr.getMapId())) {
            exitFreelance();
            followPhase = FollowPhase.FOLLOW;
        }
    }

    // FOLLOW -> FREELANCE handoff: end the tailing session, hand movement to the grind engine,
    // and register into the shared combat ticker.
    private void enterFreelance(Character leader) {
        Character chr = getChr();
        GCMovement.stop(chr); // the grind engine owns movement from here
        GCMovement.setGrinding(chr, true); // grind nav guards (no idle-hang on ropes)
        grind.start(chr); // fresh heartbeat, pick a spot, disperse into the field
        TrainingBot.registerCombatTick(this);
        followPhase = FollowPhase.FREELANCE;
        if (now() - freelanceLastSayMs >= FREELANCE_SAY_GAP_MS) {
            freelanceLastSayMs = now();
            sayNode("FreelanceStart", leader);
        }
    }

    // FREELANCE -> anything else: unregister from the combat ticker and release the spot claim +
    // combat state. Safe to call when not freelancing (unregister/release are no-ops then).
    private void exitFreelance() {
        TrainingBot.unregisterCombatTick(this);
        Character chr = getChr();
        grind.release(chr);
        if (chr != null) {
            GCMovement.setGrinding(chr, false); // back to normal nav for follow/travel
            GCMovement.stop(chr); // clear the roam target
        }
    }

    // The shared combat ticker's ~250ms hook: run the grind engine only while actually
    // freelancing, running, and not mid-trade (the macro tick handles phase exits).
    @Override
    public void onSharedCombatTick() {
        if (followPhase != FollowPhase.FREELANCE || getState() == BotState.TRADING) {
            return;
        }
        Character chr = getChr();
        if (chr == null || !getRunning()) {
            return;
        }
        grind.tick(chr);
    }

    private void doLeaderLost() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader != null && leader.getMap() != null) {
            followPhase = FollowPhase.FOLLOW;
            return;
        }
        if (wasPartied && chr.getParty() == null) {
            fallbackConvert();
            return;
        }
        if (now() - leaderLostSinceMs > LEADER_LOST_GRACE_MS) {
            sayNode("PartyFarewell", null);
            fallbackConvert();
        }
    }

    // ── Menu (Dispatcher routes a "botname" chat here via displayCommands) ───

    @Override
    public void displayCommands(Character chr) {
        menu.show(chr);
    }

    private void onMenuSelect(int idx, Character player) {
        Character chr = getChr();
        if (idx == 0) { // Come to me! (rally: regroup on the leader for a while)
            if (player.getId() != leaderId) {
                sayNode("LoyalToLeader", player);
                menu.close(player);
                return;
            }
            rallyUntilMs = now() + RALLY_HOLD_MS;
            if (followPhase == FollowPhase.FREELANCE) {
                exitFreelance();
                followPhase = FollowPhase.FOLLOW;
            }
            if (!GCMovement.isFollowing(chr)) {
                Character leader = resolveLeader();
                if (leader != null && leader.getMap() != null) {
                    GCMovement.follow(chr, leader);
                }
            }
            sayNode("Rally", player);
            menu.close(player);
            return;
        }
        if (idx == 1) { // Train here with me!
            if (player.getId() != leaderId) {
                sayNode("LoyalToLeader", player);
                menu.close(player);
                return;
            }
            if (MapMobIndex.level(chr.getMapId()) < 0) {
                sayNode("NoMobsHere", player);
                menu.close(player);
                return;
            }
            sayNode("StationHere", player);
            menu.close(player);
            if (followPhase == FollowPhase.FREELANCE) {
                exitFreelance();
            }
            BotRecruitManager.markStationHere(chr.getId());
            GCMovement.stop(chr);
            BotTypeManager.convertBotType(chr, BotTypeManager.BotType.TRAINING_BOT);
            return;
        }
        sayNode("Goodbye", player);
        menu.close(player);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Resolve the live leader from the channel's player storage each use (OPQ pattern): an id
    // survives relog, a Character reference doesn't.
    private Character resolveLeader() {
        if (leaderId <= 0) {
            return null;
        }
        return getChr().getClient().getChannelServer().getPlayerStorage().getCharacterById(leaderId);
    }

    private int leaderIdFromParty() {
        Party party = getChr().getParty();
        if (party == null) {
            return -1;
        }
        Character lead = party.getLeader() != null ? party.getLeader().getPlayer() : null;
        if (lead != null && !isBot(lead)) {
            return lead.getId();
        }
        Character member = firstRealMember(party);
        return member == null ? -1 : member.getId();
    }

    private Character firstRealMember(Party party) {
        for (PartyCharacter pc : party.getMembers()) {
            Character p = pc == null ? null : pc.getPlayer();
            if (p != null && !isBot(p) && p.getMap() != null) {
                return p;
            }
        }
        return null;
    }

    // The ride ended: become a TrainingBot on a mob map, a SocialBot in a town. Callers speak
    // their own farewell first; this only re-types.
    private void fallbackConvert() {
        Character chr = getChr();
        BotRecruitManager.clearArmed(chr.getId());
        GCMovement.stop(chr);
        boolean grindable = MapMobIndex.level(chr.getMapId()) >= 0;
        BotTypeManager.convertBotType(chr,
                grindable ? BotTypeManager.BotType.TRAINING_BOT : BotTypeManager.BotType.SOCIAL_BOT);
    }

    private void sayNode(String node, Character player) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, node, chr, player);
            if (line != null) {
                BotSpeak(chr, line);
            }
        } catch (Exception e) {
            // a missing dialogue node must never break the follow loop
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public synchronized void stopScheduledTask() {
        exitFreelance(); // drop the shared-ticker registration + spot claim (no-ops when not freelancing)
        Character chr = getChr();
        if (chr != null) {
            BotRecruitManager.clearArmed(chr.getId()); // pending station/leader handoffs survive on purpose
            BotAttackDriver.clearBot(chr.getId()); // release the attack cooldown table entry
            GCMovement.disable(chr); // ends the follow session + releases the shared movement lock
        }
        super.stopScheduledTask();
        log("[FollowerBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }
}
