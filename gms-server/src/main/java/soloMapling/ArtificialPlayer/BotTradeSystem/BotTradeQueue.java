package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.DebugUtilities.debugprint;

public class BotTradeQueue {

    // Written from netty threads (Trade.inviteTrade) and read from bot tick virtual threads.
    private final Map<Character, Character> queues;
    private static final BotTradeQueue botTradeQueue = new BotTradeQueue();

    private BotTradeQueue() {
        queues = new ConcurrentHashMap<>();
    }

    public static BotTradeQueue getInstance() {
        return botTradeQueue;
    }

    public void addTradeRequest(Character fakechar, Character partner) {
        debugprint("addTradeRequest");
        queues.putIfAbsent(fakechar, partner);
        // 立刻唤醒 bot 的宏脑，让下一个 tick 就能接受邀请——否则慢速调度下
        // （未被观察时可达 60-120 秒）玩家的交易邀请会一直挂着没人理。
        BotRecruitManager.wakeBotForInvite(fakechar);
    }

    public Character getTradeRequest(Character fakechar) {
        if (hasPendingTrades(fakechar)) {
            return queues.get(fakechar);
        }
        return null;
    }

    public boolean hasPendingTrades(Character fakechar) {
        if (queues.containsKey(fakechar)) {
            return true;
        }
        return false;
    }

    public void removeTradeRequest(Character fakechar) {
        if (hasPendingTrades(fakechar)) {
            queues.remove(fakechar);
        }
    }


}
