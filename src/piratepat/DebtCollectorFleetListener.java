package piratepat;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.fleet.FleetGoal;

/**
 * Everything about a collector encounter that happens outside the dialog:
 * closing the debt when the player has destroyed the last officer of a
 * collector fleet (the officer rule, PirateDebt.reportCollectorEliminated),
 * and - author decision D1 - letting a collector that beat the player in a
 * stand-up fight take payment out of the holds.
 *
 * <p><b>THIS CLASS MUST NEVER GAIN AN INSTANCE FIELD, MUST NEVER BE DELETED,
 * AND MUST NEVER BE RENAMED.</b> An instance is attached to every collector
 * with CampaignFleetAPI.addEventListener, which means it is serialized INSIDE
 * each live collector fleet, exactly like the HunterFleetListener that this
 * mod can now never delete. 0.98a-RC8 does not enable XStream's
 * ignoreUnknownElements, so a class name welded into a save is permanent and
 * a field element removed later is a hard load failure for any save with a
 * collector still in space. Field-free, its serialized form is an empty
 * element that nothing can break. All of its state lives in the fleet-memory
 * keys owned by PirateDebt (critique F7). The superclass is chosen with the
 * same rule in mind: BaseCampaignEventListener has no instance fields either.
 *
 * <p>It wears two hats, and both are needed:
 *
 * <p><b>1. FleetEventListener, attached per collector at spawn.</b>
 * reportBattleOccurred and reportFleetDespawnedToListener are the idempotent
 * backstops for the PiratepatCollectorBeaten defeat trigger, which only
 * fires through FID.winningPath - so it misses a fight the player LOST while
 * still killing every officer, and any fleet whose trigger was consumed by
 * an earlier partial win and not re-armed. reportBattleOccurred is also the
 * D1 seizure backstop. A collector destroyed by a patrol, a station or
 * another mod's fleet is simply gone: the player earned nothing and the
 * manager sends a replacement on its next roll.
 *
 * <p><b>2. CampaignEventListener, registered ONCE, TRANSIENTLY, at sector
 * level.</b> PiratePatModPlugin.onGameLoad must do exactly:
 * <code>Global.getSector().addTransientListener(new DebtCollectorFleetListener());</code>
 * Without that line the D1 loss branch does not exist: reportPlayerEngagement
 * is the ONLY hook in the API that fires when the player fought and lost, and
 * NOT addListenerManager - CampaignEventListener is a SectorAPI-level
 * listener and the ListenerManager has no engagement-result equivalent. A
 * transient listener is never written into the save, and the fleet-attached
 * instances never receive sector-level callbacks, so the two roles cannot
 * collide.
 */
public class DebtCollectorFleetListener extends BaseCampaignEventListener
		implements FleetEventListener {

	/**
	 * super(false) - never self-register. The manager attaches instances to
	 * fleets; the mod plugin registers exactly one transiently at sector
	 * level. XStream never calls this on load, which is fine, because there
	 * is no state to rebuild.
	 */
	public DebtCollectorFleetListener() {
		super(false);
	}

	// ------------------------------------------------------------------
	// FleetEventListener - one instance per collector, inside the fleet
	// ------------------------------------------------------------------

	/**
	 * The collector is gone. A fleet with no ships has no officers, so if the
	 * player was in the battle this is the officer rule closing - the backstop
	 * for the case where reportBattleOccurred did not reach us first (it runs
	 * before the despawn in FleetEncounterContext, so normally it does, and
	 * COUNTED_KEY makes this a no-op). Somebody else's kill is nobody's win:
	 * no rep, no ending, and the manager's next sweep finds the slot empty.
	 */
	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
			FleetDespawnReason reason, Object param) {
		if (fleet == null) return;
		if (reason != FleetDespawnReason.DESTROYED_BY_BATTLE) return;
		if (!(param instanceof BattleAPI)) return;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return;

		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		// a settled collector was flying home and is nobody's business any
		// more; it also no longer carries COLLECTOR_KEY, per critique F8
		if (mem.getBoolean(PirateDebt.SETTLED_KEY)) return;
		if (PirateDebt.isCounted(fleet)) return;

		BattleAPI battle = (BattleAPI) param;
		if (battle.isPlayerInvolved()) {
			PirateDebt.reportCollectorEliminated(fleet, null);
		} else if (PiratePatConfig.debugLogging()) {
			PirateDebt.log.info("Debt collector destroyed by a third party; a replacement "
					+ "will be dispatched");
		}
	}

	/**
	 * Battle over, with the player in it. Two jobs, in this order.
	 *
	 * <p>First the D1 seizure backstop, for the case where the engagement
	 * hook decided the player lost a stand-up fight but could not act on it.
	 * It is ONLY a backstop, and it is gated on a flag rather than on the
	 * battle result, because battle.wasFleetVictorious CANNOT tell a lost
	 * fight from a clean disengage: both route through FID.losingPath, which
	 * marks the player's side disengaged, and "winner" is then defined purely
	 * as the side that did not disengage. Running away must not trigger a
	 * seizure, so the only thing trusted here is LOST_FIGHT_KEY, which
	 * reportPlayerEngagement sets after checking that both engagement goals
	 * were ATTACK.
	 *
	 * <p>Then THE OFFICER RULE. Losses have been applied to the fleet by now
	 * (applyResultToFleets runs the moment combat returns), so the live
	 * member list is the truth: no officered hull left means nobody is left
	 * to collect, whoever "won" - a player who lost the fight but took every
	 * officer down with them is still free of the debt. This fires before
	 * FleetEncounterContext despawns an emptied fleet, so a total wipe is
	 * closed here too and the despawn hook finds COUNTED_KEY already set.
	 *
	 * <p>Text written from here is never read - the FID calls
	 * applyAfterBattleEffectsIfThereWasABattle() and then dialog.dismiss() in
	 * the same breath - so both jobs report through the message log. The
	 * narrated version of the officer rule is the defeat trigger, which fires
	 * earlier and leaves COUNTED_KEY behind for this one to respect.
	 */
	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
			BattleAPI battle) {
		if (fleet == null || battle == null) return;
		if (!battle.isPlayerInvolved()) return;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return;

		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		if (mem.getBoolean(PirateDebt.SETTLED_KEY)) return;

		seizeIfLost(fleet, mem, primaryWinner, battle);

		if (!battle.isInvolved(fleet) || battle.onPlayerSide(fleet)) return;
		if (PirateDebt.officersRemaining(fleet) > 0) return;
		PirateDebt.reportCollectorEliminated(fleet, null);
	}

	/** The D1 seizure backstop - see reportBattleOccurred. */
	protected void seizeIfLost(CampaignFleetAPI fleet, MemoryAPI mem,
			CampaignFleetAPI primaryWinner, BattleAPI battle) {
		if (!PiratePatConfig.debtSeizeEnabled()) return;
		if (!mem.getBoolean(PirateDebt.LOST_FIGHT_KEY)) return;
		// already handled where there was a text panel to write into
		if (mem.getBoolean(PirateDebt.SEIZED_KEY)) return;
		// the collector has to have survived to board anybody
		if (!battle.wasFleetVictorious(fleet, primaryWinner)) return;
		if (fleet.getFleetData().getMembersListCopy().isEmpty()) return;

		mem.unset(PirateDebt.LOST_FIGHT_KEY);
		// claim the slot BEFORE seizing: seizeByForce deliberately does not
		// set SEIZED_KEY, because this path has to claim it before it knows
		// whether anything will be taken
		mem.set(PirateDebt.SEIZED_KEY, true, 0f);

		float taken = PirateDebt.seizeByForce(fleet, null);
		if (taken >= 1f) PirateDebt.reportSeizure(taken);
	}

	// ------------------------------------------------------------------
	// CampaignEventListener - one transient instance, at sector level
	// ------------------------------------------------------------------

	/**
	 * AUTHOR DECISION D1, the primary path: the collector collects BY FORCE.
	 *
	 * <p>This is the only hook in the API that fires when the player fought
	 * and lost. There is no defeat trigger for the player -
	 * FID.losingPath() fires no rules and no triggers at all - and this fires
	 * from FleetEncounterContext.processEngagementResults the instant combat
	 * returns, while the interaction dialog is still open and still printing,
	 * so the seizure lands in the post-battle report where the player will
	 * read it.
	 *
	 * <p>THE GATE IS NOT didPlayerWin() == false. A clean successful
	 * disengage is reported as ESCAPE_PLAYER_SUCCESS with the AI side as the
	 * winner result, and a pursuit the collector escapes is ESCAPE_ENEMY_WIN;
	 * both have didPlayerWin() false, and vanilla itself counts them as
	 * player wins. Requiring BOTH engagement goals to be ATTACK narrows it to
	 * a genuine stand-up fight the player lost. Without that, running away
	 * would empty the player's hold.
	 */
	@Override
	public void reportPlayerEngagement(EngagementResultAPI result) {
		if (result == null) return;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return;
		if (!PiratePatConfig.debtSeizeEnabled()) return;
		if (result.didPlayerWin()) return;
		if (!PirateDebt.hasDebt()) return;

		BattleAPI battle = result.getBattle();
		if (battle == null || !battle.isPlayerInvolved()) return;

		EngagementResultForFleetAPI winner = result.getWinnerResult();
		EngagementResultForFleetAPI loser = result.getLoserResult();
		if (winner == null || loser == null || winner == loser) return;

		EngagementResultForFleetAPI playerRes = winner.isPlayer() ? winner : loser;
		EngagementResultForFleetAPI otherRes = winner.isPlayer() ? loser : winner;
		if (!playerRes.isPlayer()) return;

		// the discriminator: a fight, not a disengage and not a failed chase
		if (playerRes.getGoal() != FleetGoal.ATTACK) return;
		if (otherRes.getGoal() != FleetGoal.ATTACK) return;

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || !player.isValidPlayerFleet()) return;

		// This fires BEFORE applyResultToFleets, so nothing has been removed
		// from either fleet yet and a member count proves nothing on its own.
		// Read the losses off the engagement result instead. Mutual
		// destruction leaves nobody to board anything; a total player wipe
		// means the respawn dialog is about to replace the player fleet
		// object outright, and seizing from a fleet that is about to be
		// discarded would write off the balance for free.
		if (isWipedOut(otherRes)) return;
		if (isWipedOut(playerRes)) return;

		CampaignFleetAPI collector = findCollector(battle);
		if (collector == null) return;

		MemoryAPI mem = collector.getMemoryWithoutUpdate();
		if (mem.getBoolean(PirateDebt.SEIZED_KEY)) return;

		// Tell the backstop this was a genuine lost stand-up fight. It is set
		// before the seizure is attempted, so that if anything below declines
		// to act the backstop still gets its chance.
		mem.set(PirateDebt.LOST_FIGHT_KEY, true, 0f);

		InteractionDialogAPI dialog = Global.getSector().getCampaignUI() == null
				? null : Global.getSector().getCampaignUI().getCurrentInteractionDialog();
		TextPanelAPI text = dialog != null ? dialog.getTextPanel() : null;

		mem.set(PirateDebt.SEIZED_KEY, true, 0f);
		float taken = PirateDebt.seizeByForce(collector, text);
		if (taken < 1f) return;

		if (text != null) {
			// seizeByForce already wrote the itemised losses into the dialog;
			// only the ledger line is still owed
			PiratePatData.addLedger("Collectors took payment out of your holds", taken);
		} else {
			// autoresolve, or a battle with no dialog behind it: reportSeizure
			// posts the message AND writes the ledger line
			PirateDebt.reportSeizure(taken);
		}
	}

	/**
	 * Find the collector on the losing side of a battle the player just lost.
	 *
	 * <p>EngagementResultForFleetAPI.getFleet() returns a BattleAPI COMBINED
	 * fleet, never a campaign fleet, so it can never be used to read fleet
	 * memory. The pre-battle snapshot of the non-player side is the only
	 * honest place to look, and it also still contains fleets that were
	 * eliminated during the fight.
	 */
	protected static CampaignFleetAPI findCollector(BattleAPI battle) {
		CampaignFleetAPI found = pickCollector(battle.getNonPlayerSideSnapshot());
		if (found != null) return found;
		return pickCollector(battle.getNonPlayerSide());
	}

	/** First live collector in a side list, skipping anything already stood down. */
	protected static CampaignFleetAPI pickCollector(List<CampaignFleetAPI> side) {
		if (side == null) return null;
		for (CampaignFleetAPI curr : side) {
			if (curr == null) continue;
			MemoryAPI mem = curr.getMemoryWithoutUpdate();
			if (!mem.getBoolean(PirateDebt.COLLECTOR_KEY)) continue;
			if (mem.getBoolean(PirateDebt.SETTLED_KEY)) continue;
			return curr;
		}
		return null;
	}

	/**
	 * Whether a side lost everything it had. Counted off the engagement result
	 * rather than the fleet, because losses have not been applied to the
	 * fleets yet when reportPlayerEngagement runs.
	 */
	protected static boolean isWipedOut(EngagementResultForFleetAPI res) {
		if (res == null) return false;
		CampaignFleetAPI combined = res.getFleet();
		if (combined == null || combined.getFleetData() == null) return false;
		int total = combined.getFleetData().getMembersListCopy().size();
		if (total <= 0) return true;
		int lost = res.getDestroyed().size() + res.getDisabled().size();
		return lost >= total;
	}
}
