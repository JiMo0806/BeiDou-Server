package soloMapling.ArtificialPlayer.BotTradeSystem;

import org.gms.client.Character;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.Item;
import org.gms.server.Trade;
import soloMapling.ArtificialPlayer.BotBlockList;
import soloMapling.ArtificialPlayer.BotFlavorSystem.BotReactionFlavor;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.FreeMarket.FMEquip;
import soloMapling.FreeMarket.FMItem;
import soloMapling.server.BotTiming;

import java.util.List;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.convertItemIdToName;
import static soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands.getTradePartnerCharacter;
import static soloMapling.DebugUtilities.debugprint;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateDarkScrollsList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateETCList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateItem;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generatePotionsList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateRandomEquipList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateScrollsList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateThiefStarsList;
import static soloMapling.FreeMarket.FMEconomyManager.formatPriceToShorthand;
import static soloMapling.FreeMarket.FMEconomyManager.priceAdjustmentRules;
import static soloMapling.itemPool.ItemUtilities.getItemMarketValue;
import static soloMapling.itemPool.ItemUtilities.isEquip;
import static soloMapling.server.SoloMaplingUtilities.random;

public class BotTradeSM {

    public enum TradeState {
        INITIALIZE,
        WAITING_RESPONSE,
        RESPONDING,
        AWAITING_CONFIRMATION,
        CONFIRMING,
        CONFIRMED_LOCKED,
        CLEANUP,
        COMPLETED,
        TIMED_OUT,
        DECLINE,
    }

    public enum TradeMode {
        SELLING,
        BUYING,
        NULL
    }

    private BotSM parentSM;
    private boolean offerAccepted = false;
    private boolean tradeComplete = false;
    private TradeMode tradeMode = TradeMode.NULL;
    private Trade.TradeResult lastTradeResult = null;

    // 议价状态：底价（低于就免谈）、已还价次数、上次还价时间
    private int floorPrice = 0;
    private int haggles = 0;
    private long lastCounterMs = 0;
    private static final int MAX_HAGGLES = 3;
    private static final long COUNTER_INTERVAL_MS = 3000;

    // 交易收尾的本地台词池（LLM 不可用/未启用时的回落，行为与之前完全一致）
    private static final String[] TRADE_THANKS_LINES = {
            "谢谢惠顾！", "合作愉快！", "慢走啊，下次再来！", "成交愉快~"
    };
    private static final String[] TRADE_CANCELLED_LINES = {
            "你怎么取消了呀？", "哎呀，白忙活了", "好吧好吧……", "下次想好再来找我"
    };


    protected BotTradeSM.TradeState tradeState;

    private long startTime;
    private long endTime;
    // SM NOTE: 60s was too tight for players who want to chat/haggle inside the trade window.
    // 180s, and the timer resets whenever the player chats (see onPlayerChat).
    private long timeoutSeconds = 180;

    public BotTradeSM(BotSM parent) {
        this(parent, TradeMode.SELLING);
    }

    public BotTradeSM(BotSM parent, TradeMode mode) {
        this.parentSM = parent;
        this.tradeMode = mode;
        setTradeState(TradeState.INITIALIZE);
        setTradeStartTime();
    }

    protected void setTradeStartTime() {
        startTime = System.currentTimeMillis();
    }

    private BotSM getParent() {
        return this.parentSM;
    }

    private Character getChr() {
        return getParent().getChr();
    }

    public TradeMode getTradeMode() {
        return this.tradeMode;
    }

    protected void setTradeState(BotTradeSM.TradeState tradeState) {
        this.tradeState = tradeState;
    }

    protected BotTradeSM.TradeState getTradeState() {
        return this.tradeState;
    }

    protected boolean calculateTimeOut() {
        endTime = startTime + (timeoutSeconds * 1000);
        if (System.currentTimeMillis() > endTime) {
            debugprint("Timed out");
            return true;
        }
        return false;
    }

    protected boolean isSelling() {
        return getTradeMode() == TradeMode.SELLING;
    }

    protected boolean isBuying() {
        return getTradeMode() == TradeMode.BUYING;
    }

    public void update() {
        switch (getTradeState()) {
            case INITIALIZE:
                startTradeCallback();
                // 通用 bot（没有自营商品的类型）随机上一件货并按行情定价，让任意 bot 都能和玩家交易
                maybeStockRandomGoods();
                if (isSelling()) {
                    // Selling flow: Post items first, then wait for response
                    if (postItemsForSale()) {
                        setTradeState(TradeState.RESPONDING);
                    } else {
                        setTradeState(TradeState.WAITING_RESPONSE);
                    }
                } else if (isBuying()) {
                    // Buying flow: Show what we're looking for first
                    BotTradeCommands.writeTradeChat(getChr(), generateWantsMessageString());
                    setTradeState(TradeState.WAITING_RESPONSE);
                } else {
                    //null
                    BotTradeCommands.writeTradeChat(getChr(), "我现在手头没什么可交易的");
                    setTradeState(TradeState.WAITING_RESPONSE);
                }
                break;
            case RESPONDING:
                if (isSelling()) {
                    // Selling mode: Tell what we want in exchange
                    setTradeStartTime();
                    BotTradeCommands.writeTradeChat(getChr(), generateWantsMessageString());
                } else if (isBuying() && isCorrectItemOffered()) {
                    // Buying mode: Respond with mesos/items we're offering
                    postMesosForBuying();
                    setTradeState(TradeState.CONFIRMING);
                    break;
                }
                setTradeState(TradeState.WAITING_RESPONSE);
                break;
            case WAITING_RESPONSE:
                if (isSelling()) {
                    // Selling flow: Wait for partner to offer what we want
                    if (isSufficientToAccept()) {
                        setTradeState(TradeState.CONFIRMING);
                        break;
                    }
                    if (BotTradeCommands.isPartnerLocked(getChr())) {
                        // 玩家已点确认但出价不足：过底价就认了，否则走人
                        if (floorPrice > 0 && BotTradeCommands.readPartnerMeso(getChr()) >= floorPrice) {
                            BotTradeCommands.writeTradeChat(getChr(), "行吧，就按你出的价成交！");
                            setTradeState(TradeState.CONFIRMING);
                        } else {
                            setTradeState(TradeState.DECLINE);
                        }
                        break;
                    }
                    tryCounterOffer(); // 玩家出了低于开价的金币：还价
                } else if (isBuying()) {
                    // Buying flow: Wait for partner to offer what we want to buy
                    if (isCorrectItemOffered()) {
                        debugprint("good item!");
                        setTradeState(TradeState.RESPONDING);
                        break;
                    }
                    if (BotTradeCommands.isPartnerLocked(getChr()) && !isCorrectItemOffered()) {
                        setTradeState(TradeState.DECLINE);
                        break;
                    }
                }
                break;
            case CONFIRMING:
                setTradeStartTime();
                setOfferAccepted();
                BotTradeCommands.writeTradeChat(getChr(), "成交，没问题！");
                BotTradeCommands.confirmTrade(getChr());
                setTradeState(TradeState.CONFIRMED_LOCKED);
                break;
            case CONFIRMED_LOCKED:
                debugprint("CONFIRMED LOCKED");
                if (getParent().getTradeHandler().verifyTradePartner()) {
                    break;
                }
                setTradeState(TradeState.CLEANUP);
                break;

            case CLEANUP:
                getParent().getTradeInventory().resetItemsForSale();
                getParent().getTradeWants().resetTradeWants();
                getParent().setTradeMode(TradeMode.NULL);

                if (lastTradeResult != Trade.TradeResult.SUCCESSFUL) {
                    BotEmote(getChr(), 4);
                    BotReactionFlavor.speakFlavor(getChr(),
                            "（旁白：交易刚被对方取消了，说一句简短的抱怨，不超过10个字）",
                            TRADE_CANCELLED_LINES);
                } else {
                    BotEmote(getChr(), 2);
                    BotReactionFlavor.speakFlavor(getChr(),
                            "（旁白：交易刚顺利成交，说一句简短的道别或感谢，不超过10个字）",
                            TRADE_THANKS_LINES);
                    getParent().setLastTradeResult(Trade.TradeResult.SUCCESSFUL);
                }
                getParent().waitFor(2000); // farewell beat before COMPLETED ticks
                lastTradeResult = null;
                setTradeState(TradeState.COMPLETED);
                break;
            case COMPLETED:
                debugprint("COMPLETED");
                setTradeCompleted();
                break;
            case TIMED_OUT:
                BotTradeCommands.writeTradeChat(getChr(), "等太久了，我先走一步！");
                // decline (closes the trade window) lands 2s after the message; hold
                // the bot slightly past it so nothing runs while the window is open
                BotTiming.after(2000, () -> BotTradeCommands.declineTradeInvite(getChr()));
                getParent().waitFor(2500);
                setTradeCompleted();
                setTradeState(TradeState.COMPLETED);
                break;
            case DECLINE:
                declineTradeOffer();
                setTradeState(TradeState.COMPLETED);
                break;
            default:
                throw new IllegalStateException("Unexpected state: " + tradeState);
        }
        if (calculateTimeOut()) {
            setTradeState(TradeState.TIMED_OUT);
        }
    }

    protected boolean isSufficientToAccept() {
        if (getParent().getTradeWants().verifyTrade(BotTradeCommands.readPartnerMeso(getChr()),
                BotTradeCommands.getPartnersItems(getChr()))) {
            return true;
        }
        return false;
    }

    protected boolean isCorrectItemOffered() {
        List<Item> partnerItems = BotTradeCommands.getPartnersItems(getChr());
        return getParent().getTradeWants().verifySufficientItems(partnerItems);
    }

    protected void postMesosForBuying() {
        int mesoOffering = getParent().getTradeWants().getMesoOffering();
        if (mesoOffering > 0) {
            BotTradeCommands.setMeso(getChr(), mesoOffering);
            BotTradeCommands.writeTradeChat(getChr(), "这是 " + formatPriceToShorthand(mesoOffering) + " 金币，收好你的东西！");
        }

        // Also offer any items we might be exchanging
//        List<Item> itemsOffering = getParent().getTradeWants().getItemsOffering();
//        if (itemsOffering != null && !itemsOffering.isEmpty()) {
//            for (Item item : itemsOffering) {
//                if (item instanceof Equip) {
//                    BotTradeCommands.addEquipToTrade(getChr(), (Equip)item, 1);
//                } else {
//                    BotTradeCommands.addItemToTrade(getChr(), item, item.getQuantity());
//                }
//            }
//        }

        // Move to confirming if we're satisfied with the trade

    }

    protected boolean waitForPartnerMesoOffer(int mesoOffer) {
        if (BotTradeCommands.readPartnerMeso(getChr()) >= mesoOffer) {
            return true;
        }
        return false;
    }

    public void setTradeCompleted() {
        tradeComplete = true;
    }

    public boolean isTradeComplete() {
        return tradeComplete;
    }

    public void setOfferAccepted() {
        offerAccepted = true;
    }

    public boolean isOfferAccepted() {
        return offerAccepted;
    }

    protected boolean postItemsForSale() {
        Item itemForSale = getParent().getTradeInventory().getMainItemForSale();
        if (itemForSale == null) {
            BotTradeCommands.writeTradeChat(getChr(), "抱歉，我现在没有可卖的东西");
            return false;
        }

        if (isEquip(itemForSale)) {
            Equip eqForSale = (Equip) getParent().getTradeInventory().getMainItemForSale();
            BotTradeCommands.addEquipToTrade(getChr(), eqForSale, 1);
            BotTradeCommands.writeTradeChat(getChr(), "看看我带来的货，瞧一瞧！");
            return true;
        } else {
            BotTradeCommands.addItemToTrade(getChr(), itemForSale.getItemId(), 1, 1);
            BotTradeCommands.writeTradeChat(getChr(), "看看我带来的货，瞧一瞧！");
            return true;
        }
    }

    // ── 随机上货 + 议价（通用 bot 交易） ─────────────────────────────────────

    // 没有自营商品的 bot（SocialBot / TrainingBot 等一切 NULL 模式类型）开张时随机
    // 挑一件货：开价在行情 0.9x~1.3x，底价 0.55x~0.75x，留出议价空间。
    private void maybeStockRandomGoods() {
        if (getTradeMode() != TradeMode.NULL) {
            return; // 商人型 bot 的自营流程不动
        }
        // 1/6 概率先试装备：装备直接上架 Equip 实例（保留随机属性/卷痕迹）
        if (random.nextInt(6) == 4 && stockRandomEquip()) {
            return;
        }
        List<FMItem> pool = randomShopList();
        if (pool == null || pool.isEmpty()) {
            return;
        }
        FMItem pick = pool.get(random.nextInt(pool.size()));
        Item item = generateItem(pick.getItemId(), 1, 1);
        if (item == null) {
            return;
        }
        stockItemForSale(item, pick.getItemId());
    }

    private List<FMItem> randomShopList() {
        return switch (random.nextInt(5)) {
            case 0 -> generateScrollsList("A");
            case 1 -> generateDarkScrollsList("A");
            case 2 -> generateThiefStarsList("A");
            case 3 -> generateETCList("A");       // 怪物材料：水晶/矿石/宝石原石与成品
            default -> generatePotionsList("S");
        };
    }

    // 交易用的装备池：随机一个职业池（含通用池），挑一件完整 Equip 上架。
    private boolean stockRandomEquip() {
        List<FMEquip> equips = generateRandomEquipList("A");
        if (equips == null || equips.isEmpty()) {
            return false;
        }
        FMEquip pick = equips.get(random.nextInt(equips.size()));
        Equip equip = pick == null ? null : pick.getEquip();
        if (equip == null) {
            return false;
        }
        return stockItemForSale(equip, equip.getItemId());
    }

    // 上架+定价：开价在行情 0.9x~1.3x，底价 0.55x~0.75x，留出议价空间。
    private boolean stockItemForSale(Item item, int itemId) {
        Integer rawValue = getItemMarketValue(item);
        if (rawValue == null || rawValue <= 0) {
            return false; // 没有行情数据的货不上架
        }
        int market = rawValue;
        getParent().getTradeInventory().setItemForSaleMain(item);
        getParent().getTradeWants().resetTradeWants();
        double askFactor = 0.9 + random.nextDouble() * 0.4;      // 开价 0.9x ~ 1.3x 行情
        double floorFactor = 0.55 + random.nextDouble() * 0.2;   // 底价 0.55x ~ 0.75x 行情
        floorPrice = Math.max(1, (int) (market * floorFactor));
        int ask = Math.max(floorPrice, priceAdjustmentRules((int) (market * askFactor)));
        getParent().getTradeWants().setMesoWanted(ask);
        this.tradeMode = TradeMode.SELLING;
        debugprint("stockRandomGoods: " + getChr().getName() + " stocks item " + itemId
                + " ask=" + ask + " floor=" + floorPrice);
        return true;
    }

    // 玩家放了金币但低于开价：在玩家出价和开价之间各让一步地还价，最多 MAX_HAGGLES 次、
    // 每次间隔至少 COUNTER_INTERVAL_MS，绝不低于底价。放宽的 mesoWanted 会随还价一路下调，
    // isSufficientToAccept 的容差也随之放宽，玩家补到还价金额即可成交。
    private void tryCounterOffer() {
        int offered = BotTradeCommands.readPartnerMeso(getChr());
        if (offered <= 0 || floorPrice <= 0 || haggles >= MAX_HAGGLES) {
            return;
        }
        setTradeStartTime(); // 玩家在动钱，还活跃，别让倒计时掐了交易
        long now = System.currentTimeMillis();
        if (now - lastCounterMs < COUNTER_INTERVAL_MS) {
            return; // 别刷屏
        }
        int asking = getParent().getTradeWants().getMesoWanted();
        if (offered >= asking) {
            return; // 容差内足够，等锁盘分支处理
        }
        int counter = offered + (asking - offered) * 2 / 3; // 偏向卖方的中间价
        counter = Math.max(counter, floorPrice);
        if (counter >= asking) {
            return; // 没有降价空间
        }
        lastCounterMs = now;
        haggles++;
        getParent().getTradeWants().setMesoWanted(counter);
        String line = switch (haggles) {
            case 1 -> "这个价太低了，" + formatPriceToShorthand(counter) + " 卖你，怎么样？";
            case 2 -> "再让就要亏本了，" + formatPriceToShorthand(counter) + "，最后价！";
            default -> "真是拿你没办法，" + formatPriceToShorthand(counter) + "，不能再少了！";
        };
        BotTradeCommands.writeTradeChat(getChr(), line);
        debugprint("counterOffer: " + getChr().getName() + " asks " + counter
                + " (offered " + offered + ", floor " + floorPrice + ")");
    }

    /**
     * Generates a simple message describing what the user wants in a trade.
     *
     * @return A string that describes the mesos and/or items wanted.
     */
    protected String generateWantsMessageString() {
        int mesoWanted = getParent().getTradeWants().getMesoWanted();
        List<ItemQuantity> itemsWanted = getParent().getTradeWants().getItemsWanted();
        StringBuilder wantsMessage = new StringBuilder("我想要 ");

        // Meso part
        if (mesoWanted > 0) {
//            wantsMessage.append(mesoWanted).append(" mesos");
            wantsMessage.append(formatPriceToShorthand(mesoWanted));
            // Add "and" if there are also items
            if (itemsWanted != null && !itemsWanted.isEmpty()) {
                wantsMessage.append(" and ");
            }
        }

        // Items part
        if (itemsWanted != null && !itemsWanted.isEmpty()) {
            if (itemsWanted.size() == 1) {
                ItemQuantity item = itemsWanted.get(0);
                String itemName = convertItemIdToName(item.getItemId());
                if (item.getQuantity() > 1) {
                    wantsMessage.append("").append(item.getQuantity()).append("x ").append(itemName);
                } else {
                    wantsMessage.append("").append(itemName);
                }
            } else {
                wantsMessage.append("");
                for (int i = 0; i < itemsWanted.size(); i++) {
                    ItemQuantity item = itemsWanted.get(i);
                    String itemName = convertItemIdToName(item.getItemId());

                    if (item.getQuantity() > 1) {
                        wantsMessage.append("").append(item.getQuantity()).append("x ").append(itemName);
                    } else {
                        wantsMessage.append("").append(itemName);
                    }

                    if (i < itemsWanted.size() - 1) {
                        wantsMessage.append(", ");
                    }
                }
            }
        }
//        if (itemsWanted != null && !itemsWanted.isEmpty()) {
//            if (itemsWanted.size() == 1) {
//                wantsMessage.append("").append(convertItemIdToName(itemsWanted.get(0).getItemId()));
//            } else {
//                wantsMessage.append("");
//                for (int i = 0; i < itemsWanted.size(); i++) {
//                    wantsMessage.append("").append(convertItemIdToName(itemsWanted.get(i).getItemId()));
//                    if (i < itemsWanted.size() - 1) {
//                        wantsMessage.append(", ");
//                    }
//                }
//            }
//        }

        // Nothing wanted
        if (mesoWanted == 0 && (itemsWanted == null || itemsWanted.isEmpty())) {
            wantsMessage.append("什么都行，看着给");
        }

        return wantsMessage.toString();
    }

    protected void declineTradeOffer() {
        BotBlockList.getInstance().addToBlockList(getChr().getId(), getTradePartnerCharacter(getChr()).getId());
        BotTradeCommands.writeTradeChat(getChr(), "这价不行，再见！");
        BotTiming.after(2000, () -> BotTradeCommands.declineTradeInvite(getChr()));
        getParent().waitFor(2500); // hold until the delayed decline lands
    }

    public void onTradeSuccess() {
        lastTradeResult = Trade.TradeResult.SUCCESSFUL;
    }

    // ── 交易窗口聊天：玩家说话 bot 要能接住 ─────────────────────────────────
    // 玩家的交易聊天只走显示包（Trade.chat），bot 的 sendPacket 是 no-op，
    // 所以必须在 PlayerInteractionHandler 里显式路由进来（BotTradeCommands.onPlayerTradeChat）。
    // 这里做三件事：重置超时（玩家还活跃）、带价格数字就走议价、否则 LLM 闲聊（回落本地台词）。
    public void onPlayerChat(Character player, String msg) {
        if (msg == null || msg.isBlank()) {
            return;
        }
        setTradeStartTime(); // 玩家还在交流，别让倒计时把交易掐了

        TradeState st = getTradeState();
        // Any live phase of the trade window answers chat (INITIALIZE..CONFIRMED_LOCKED); only the
        // terminal phases ignore it. The old 3-state gate went silent through AWAITING_CONFIRMATION /
        // CONFIRMING, exactly when a player second-guesses the deal and talks most.
        if (st == TradeState.CLEANUP || st == TradeState.COMPLETED || st == TradeState.TIMED_OUT
                || st == TradeState.DECLINE || st == TradeState.INITIALIZE) {
            return;
        }

        Integer price = parsePrice(msg);
        if (price != null && isSelling() && floorPrice > 0 && st != TradeState.CONFIRMED_LOCKED) {
            negotiateFromChat(player, price);
            return;
        }
        chatSmallTalk(player, msg);
    }

    // 从聊天里抠出价格：支持"50万""500000""50 w"等写法
    private Integer parsePrice(String msg) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)(?:\\.\\d+)?\\s*(万|w|W)?").matcher(msg);
        long best = -1;
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (m.group(2) != null) {
                v *= 10000;
            }
            best = Math.max(best, v);
        }
        if (best <= 0 || best > 2100000000L) {
            return null; // 没有像样的数字，或超出金币上限，当闲聊处理
        }
        return (int) best;
    }

    // 玩家口头报价：达到开价直接催成交；过底价各让一步；低于底价礼貌拒绝
    private void negotiateFromChat(Character player, int price) {
        int asking = getParent().getTradeWants().getMesoWanted();
        if (price >= asking) {
            BotTiming.afterRandom(600, 1400, () ->
                    BotTradeCommands.writeTradeChat(getChr(), "这个价可以！把钱放上来点确认就行"));
            return;
        }
        if (price < floorPrice) {
            int hint = floorPrice;
            BotTiming.afterRandom(600, 1400, () ->
                    BotTradeCommands.writeTradeChat(getChr(),
                            "太低啦，" + formatPriceToShorthand(hint) + " 以下我真的没法卖"));
            return;
        }
        // 底价~开价之间：还有还价次数就各让一步，否则咬死底价
        int target;
        if (haggles < MAX_HAGGLES) {
            target = Math.max(floorPrice, price + (asking - price) * 2 / 3);
            haggles++;
        } else {
            target = Math.max(floorPrice, asking - (asking - floorPrice) / 4);
        }
        if (target >= asking) {
            target = Math.max(floorPrice, asking - 1);
        }
        int finalTarget = Math.min(target, asking - 1);
        getParent().getTradeWants().setMesoWanted(finalTarget);
        String line = haggles >= MAX_HAGGLES
                ? "最多让到 " + formatPriceToShorthand(finalTarget) + " 了，再低真不卖！"
                : "这样吧，" + formatPriceToShorthand(finalTarget) + " 怎么样？诚实价！";
        BotTiming.afterRandom(700, 1600, () -> BotTradeCommands.writeTradeChat(getChr(), line));
        debugprint("negotiateFromChat: " + getChr().getName() + " oral offer " + price
                + " -> counter " + finalTarget + " (floor " + floorPrice + ")");
    }

    private static final String[] TRADE_SMALL_TALK = {
            "哈哈，你眼光不错，这可是好货",
            "价格好商量，但别砍太狠哈",
            "嗯嗯，你考虑一下，不着急",
            "这东西我收来也不容易的",
            "你要是诚心要，咱们慢慢谈",
            "嘿嘿，做买卖讲究个你情我愿",
    };

    // 交易窗口闲聊：走 LLM（带交易语境），失败或未启用回落本地台词
    private void chatSmallTalk(Character player, String msg) {
        Character bot = getChr();
        if (soloMapling.ArtificialPlayer.BotMessagingSystem.BotLLMService.ready(bot.getId())) {
            String mapName = bot.getMap().getMapName();
            String userMsg = "[交易窗口内对话，bot 正在卖货开价 " + formatPriceToShorthand(getParent().getTradeWants().getMesoWanted())
                    + " 金币] " + msg;
            Thread.ofVirtual().name("bot-tradechat-" + bot.getId()).start(() -> {
                String reply = null;
                try {
                    Thread.sleep(500 + random.nextInt(800)); // 别秒回，像人在打字
                    reply = soloMapling.ArtificialPlayer.BotMessagingSystem.BotLLMService.chat(
                            bot.getId(), bot, player.getName(), mapName, userMsg);
                } catch (Exception e) {
                    debugprint("tradechat llm error: " + e.getMessage());
                }
                if (reply != null && !reply.isBlank()) {
                    BotTradeCommands.writeTradeChat(bot, reply);
                } else {
                    String line = TRADE_SMALL_TALK[random.nextInt(TRADE_SMALL_TALK.length)];
                    BotTradeCommands.writeTradeChat(bot, line);
                }
            });
        } else {
            String line = TRADE_SMALL_TALK[random.nextInt(TRADE_SMALL_TALK.length)];
            BotTiming.afterRandom(600, 1500, () -> BotTradeCommands.writeTradeChat(getChr(), line));
        }
    }

    public void startTradeCallback() {
        Trade trade = getChr().getTrade(); /* get or create the Trade object */
        getParent().setLastTradedCharacter(BotTradeCommands.getTradePartnerCharacter(getChr()));
        // IMPORTANT: Set the callback IMMEDIATELY after getting the Trade object
        trade.setTradeResultCallback(result -> {
            if (result == Trade.TradeResult.SUCCESSFUL) {
                debugprint("**************Successful trade!");
                onTradeSuccess();
            }
        });
    }

}
