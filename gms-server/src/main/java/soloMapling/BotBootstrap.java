package soloMapling;

import org.gms.config.GameConfig;
import soloMapling.ArtificialPlayer.BotClientHandler;
import soloMapling.Casino.WzXmlPatcher;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.server.MethodScheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Single entry point for all SoloMapling server hooks. BeiDou's Server.init()
 * calls into these phase methods so future BeiDou upgrades only need to keep
 * these three call sites alive.
 */
public final class BotBootstrap {

    private BotBootstrap() {
    }

    /**
     * Phase 1: heavy WZ-derived data loading. Submit alongside the other
     * init tasks on the shared virtual-thread executor.
     */
    public static void submitDataLoadTasks(ExecutorService executor, List<Future<?>> futures) {
        SoloResourceExtractor.extractIfNeeded();
        futures.add(executor.submit(EquipMetadataCache::initialize));
        futures.add(executor.submit(DesirableEquipList::load));
    }

    /**
     * Phase 2: XML patches that must run after the WZ data is loaded but
     * before channels go live.
     */
    public static void afterDataLoad() {
        WzXmlPatcher.applyAllPatches();
    }

    /**
     * Phase 3: cold-boot bot startup. Call once everything else (worlds,
     * channels, scripts) is ready. Failures here must never take the server
     * down, hence the catch-all.
     */
    public static void onServerReady() {
        try {
            BotClientHandler.initHeadlessBotClient();
            if (GameConfig.get("server", "spawn_bots_on_startup", true)) {
                MethodScheduler.runAfterDelay(EnvironmentManager::environmentLoadStartup, 1000);
            }
        } catch (Exception e) {
            BotLogger.log("BotBootstrap.onServerReady failed: " + e);
            e.printStackTrace();
        }
    }
}
