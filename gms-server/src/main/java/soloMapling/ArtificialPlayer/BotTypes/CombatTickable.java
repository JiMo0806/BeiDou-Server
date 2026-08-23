package soloMapling.ArtificialPlayer.BotTypes;

// A bot that wants ~250ms combat cadence on the shared ticker (TrainingBot grinders,
// FollowerBot freelancers). Kept as a top-level interface — a TrainingBot-nested one
// would be cyclic inheritance (class implementing an interface nested inside itself).
// See TrainingBot.registerCombatTick / unregisterCombatTick.
public interface CombatTickable {
    void onSharedCombatTick();
}
