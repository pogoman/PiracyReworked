package piratepat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.fleets.AutoDespawnScript;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseManager;
import com.fs.starfarer.api.impl.campaign.missions.hub.MissionFleetAutoDespawn;
import com.fs.starfarer.api.util.Misc;

/**
 * Load-time wiring for the mod, and the one-way v0.1.x -> v0.2.0 save
 * migration that turns the old per-faction bounty ledger into a single debt.
 *
 * <p>There is deliberately NO configureXStream override. 0.98a-RC8 does not
 * enable XStream's global ignoreUnknownElements, so the only safe way to
 * retire a serialized class is to keep it and gut it - which is what
 * PersonalBountyIntel, BountyHunterManager and HunterFleetListener now are.
 * Adding an alias override would also drag xstream-1.4.10.jar onto compile.ps1's
 * classpath, which it does not currently carry.
 */
public class PiratePatModPlugin extends BaseModPlugin {

	public static Logger log = Global.getLogger(PiratePatModPlugin.class);

	/**
	 * The legacy per-faction bounty ledger, a LinkedHashMap&lt;String,Float&gt;.
	 * Read DIRECTLY through persistent data and never through an accessor: the
	 * old PiratePatData.bounties() lazily wrote an empty map back into
	 * persistent data on any read, so a read-through-accessor migration would
	 * resurrect the very key it is trying to remove.
	 */
	private static final String KEY_OLD_BOUNTIES = "piratepat_bounties";

	/** Legacy LinkedHashSet&lt;String&gt;. Only ever removed from, so always empty. */
	private static final String KEY_OLD_ACTIVE_BOUNTIES = "piratepat_activeBounties";

	/** Legacy Boolean. No dormant/active pool concept survives the rework. */
	private static final String KEY_OLD_POOL_ACTIVE = "piratepat_bountyPoolActive";

	/** The reason string the old bounty code used for its fleet memory flags. */
	private static final String OLD_FLAG_REASON = "piratepat_bounty";

	@Override
	public void onGameLoad(boolean newGame) {
		SectorAPI sector = Global.getSector();

		// Replace the vanilla base manager with the war chest economy. Exact
		// class match only - our subclass (from a save) must survive this.
		float carryDays = -1f;
		for (EveryFrameScript s : new ArrayList<EveryFrameScript>(sector.getScripts())) {
			if (s.getClass() == PirateBaseManager.class) {
				carryDays = ((PirateBaseManager) s).getUnadjustedDaysSinceStart();
				sector.removeScript(s);
				log.info("Removed vanilla PirateBaseManager (elapsed days carried over: "
						+ (int) carryDays + ")");
			}
		}

		PatronageBaseManager mgr = null;
		for (EveryFrameScript s : sector.getScripts()) {
			if (s instanceof PatronageBaseManager) {
				mgr = (PatronageBaseManager) s;
				break;
			}
		}
		if (mgr == null) {
			mgr = new PatronageBaseManager();
			if (carryDays > 0) mgr.setExtraDays(carryDays);
			sector.addScript(mgr);
			log.info("Installed PatronageBaseManager");
		}

		// vanilla code paths resolve the manager through this memory key
		sector.getMemoryWithoutUpdate().set(PirateBaseManager.KEY, mgr);

		mgr.adoptExistingBases();
		PiratePatData.seedIfNeeded(mgr.getBases().size());

		// v0.1.x -> v0.2.0. Steps (a)-(e) are one-shot and guarded; (f)-(h)
		// are idempotent and run on every load, because a save can acquire a
		// stale intel entry or a stale hunter through a path the guard does
		// not cover (an older save loaded, saved, and loaded again under a
		// half-updated install, say).
		migrateBountiesToDebt(sector);
		purgeBountyIntel(sector);
		purgeBountyHunterManager(sector);
		standDownLegacyHunters(sector);

		if (findCollectorManager(sector) == null) {
			sector.addScript(new DebtCollectorManager());
			log.info("Installed DebtCollectorManager");
		}

		// transient: re-added every load, never serialized into the save
		sector.getListenerManager().addListener(new BlackMarketListener(), true);
		sector.getListenerManager().addListener(new PirateHuntListener(), true);
		sector.getListenerManager().addListener(new DebtInstallmentTicker(), true);

		// AUTHOR DECISION D1, and the whole of it: without this line the
		// collector cannot collect by force. reportPlayerEngagement is the
		// only hook in the API that fires when the player fought and lost -
		// FID.losingPath() fires no rules and no defeat triggers - and it is a
		// CampaignEventListener callback, so it MUST go on the sector, not on
		// the ListenerManager, which has no engagement-result equivalent.
		//
		// The same class is also attached per collector by
		// DebtCollectorManager as a FleetEventListener. The two roles cannot
		// collide: fleet-attached instances never receive sector-level
		// callbacks, and the class is field-free either way. addTransientListener
		// means this instance is never written into the save.
		sector.addTransientListener(new DebtCollectorFleetListener());

		// the debt system is OFF by default and may be switched either way
		// mid-campaign: apply the switch to whatever this save has flying
		// right now, and - with LunaLib - the moment it is flipped in-game.
		// The sector overload, not the guarded one: the game state is not
		// reliably CAMPAIGN yet while onGameLoad is still running.
		applySettings(sector);
		if (PiratePatConfig.lunaAvailable()) LunaSettingsHook.register();

		// the broker lives on underworld contacts now (rules.csv +
		// PiratepatBrokerCMD). Purge the old bar event machinery from saves
		// that serialized it.
		BarEventManager bars = BarEventManager.getInstance();
		if (bars != null && bars.hasEventCreator(BrokerBarEventCreator.class)) {
			for (BarEventManager.GenericBarEventCreator c
					: new ArrayList<BarEventManager.GenericBarEventCreator>(bars.getCreators())) {
				if (c instanceof BrokerBarEventCreator) bars.getCreators().remove(c);
			}
			log.info("Removed deprecated BrokerBarEventCreator");
		}
		for (PortsideBarEvent e : new ArrayList<PortsideBarEvent>(
				PortsideBarData.getInstance().getEvents())) {
			if (e instanceof BrokerBarEvent) PortsideBarData.getInstance().removeEvent(e);
		}

		WarChestIntel.ensureAdded();
	}

	// ------------------------------------------------------------------
	// the switches, applied to a live sector
	// ------------------------------------------------------------------

	/** The installed collector manager, or null if this save has none yet. */
	private static DebtCollectorManager findCollectorManager(SectorAPI sector) {
		for (EveryFrameScript s : sector.getScripts()) {
			if (s instanceof DebtCollectorManager) return (DebtCollectorManager) s;
		}
		return null;
	}

	/**
	 * Re-read the master switches against the live sector. Called once on
	 * every game load, and again by {@link LunaSettingsHook} whenever the
	 * player saves the in-game settings panel, so that switching the debt
	 * system off stands every collector down on the spot and switching it
	 * back on restarts a dormant balance's notice and grace clocks.
	 *
	 * <p>Safe to call from anywhere: it does nothing outside the campaign,
	 * and the tick-driven paths in DebtCollectorManager reach the same
	 * state on their own within a fraction of a day for the player's own
	 * system - this only makes it immediate.
	 */
	public static void applySettings() {
		if (Global.getCurrentState() != GameState.CAMPAIGN) return;
		SectorAPI sector = Global.getSector();
		if (sector == null || sector.getPlayerFleet() == null) return;
		applySettings(sector);
	}

	private static void applySettings(SectorAPI sector) {
		DebtCollectorManager collectors = findCollectorManager(sector);
		if (collectors == null) return;

		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) {
			collectors.retireAll();
		} else {
			collectors.wake();
		}
	}

	// ------------------------------------------------------------------
	// (a)-(e): the one-shot bounty-to-debt transfer
	// ------------------------------------------------------------------

	/**
	 * Fold the old per-faction bounty ledger into one debt, then remove the
	 * old keys. Guarded by PirateDebt.KEY_MIGRATED_V1, which PirateDebt.clear()
	 * deliberately does NOT remove (critique F6) - paying a debt off must not
	 * un-guard the migration and hand the player their old bounties back.
	 *
	 * <p>The failure mode this method exists to avoid: calling
	 * PirateDebt.accrue() per faction, letting it early-return on the
	 * eligibility gate, then wiping the keys and setting the guard anyway. That
	 * silently destroys the entire balance for anyone whose only bounty came
	 * from a faction the new gate excludes, and for anyone whose largest poster
	 * came from a faction mod that has since been uninstalled. So: the total is
	 * summed unconditionally and never filtered, migrateOpen() is used rather
	 * than accrue() because it deliberately skips the eligibility gate, and
	 * the origin - which is flavour only - falls back twice before giving up.
	 */
	private static void migrateBountiesToDebt(SectorAPI sector) {
		// (a)
		if (PirateDebt.isMigrated()) return;

		Map<String, Object> data = sector.getPersistentData();

		// (b) Read the map directly. Values are Floats in practice, but a
		// hand-edited save or an older writer could leave a Double, so the
		// test is instanceof Number.
		Object raw = data.get(KEY_OLD_BOUNTIES);
		float total = 0f;
		int posters = 0;
		String bestEligibleId = null;
		float bestEligible = 0f;
		String bestAnyId = null;
		float bestAny = 0f;

		if (raw instanceof Map) {
			Map<?, ?> old = (Map<?, ?>) raw;
			for (Map.Entry<?, ?> entry : old.entrySet()) {
				if (!(entry.getKey() instanceof String)) continue;
				if (!(entry.getValue() instanceof Number)) continue;
				String factionId = (String) entry.getKey();
				float amount = ((Number) entry.getValue()).floatValue();
				if (amount <= 0f) continue;

				total += amount;
				posters++;
				if (amount > bestAny) {
					bestAny = amount;
					bestAnyId = factionId;
				}
				// The origin is named in flavour text, so prefer a faction the
				// new system would accept as an originator - but never let that
				// preference decide whether the balance survives.
				if (amount > bestEligible && PirateDebt.canOriginateDebt(factionId)) {
					bestEligible = amount;
					bestEligibleId = factionId;
				}
			}
		}

		// (c)
		if (total > 0f) {
			String origin = bestEligibleId;
			if (origin == null) origin = bestAnyId;
			if (origin == null) origin = Factions.INDEPENDENT;

			boolean transferred = PirateDebt.migrateOpen(origin, total);
			if (!transferred && PirateDebt.hasDebt()) {
				// Only reachable if a v0.2.0 debt somehow opened before this
				// ran. Fold the legacy total in rather than dropping it.
				PirateDebt.addPrincipal(total);
				transferred = true;
				log.info("Legacy bounty total folded into an already-open debt");
			}
			if (!transferred) {
				// Do NOT remove the keys and do NOT set the guard - leave the
				// save exactly as it was so the next load tries again.
				log.warn("Could not migrate a legacy bounty total of " + (int) total
						+ "; leaving the old keys in place to retry on the next load");
				return;
			}
			if (PiratePatConfig.enabled() && PiratePatConfig.debtEnabled()) {
				announceMigration(total, posters);
			} else {
				// the system is off (which is the default): the old prices are
				// consolidated silently and sleep until the player opts in, at
				// which point the notice and grace periods start from that day
				PirateDebt.markDormant();
				log.info("Legacy bounties consolidated into a dormant debt; the debt "
						+ "system is switched off");
			}
		}

		// (d)
		data.remove(KEY_OLD_BOUNTIES);
		data.remove(KEY_OLD_ACTIVE_BOUNTIES);
		data.remove(KEY_OLD_POOL_ACTIVE);

		// (e) AFTER the transfer, never before.
		data.put(PirateDebt.KEY_MIGRATED_V1, Boolean.TRUE);

		if (total > 0f) {
			log.info("Bounty-to-debt migration complete: " + (int) total + " credits from "
					+ posters + " poster(s) consolidated, origin " + PirateDebt.origin()
					+ ", holder " + PirateDebt.holder());
		}
	}

	/**
	 * Tell the player their books changed shape. A migration that moves this
	 * much money must never be silent, so it goes in both the ledger and the
	 * message log.
	 */
	private static void announceMigration(float total, int posters) {
		PiratePatData.addLedger("Your outstanding prices were bought up and consolidated "
				+ "into a single receivable", 0f);

		MessageIntel msg = new MessageIntel();
		msg.addLine("Your paper has been consolidated", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "A collection house in independent space bought up "
				+ "the prices on your head from " + posters + " "
				+ (posters == 1 ? "poster" : "posters") + " and holds them as one debt.",
				Misc.getTextColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Balance: %s, originating with %s",
				Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(total), PirateDebt.originName() },
				Misc.getHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Bounty hunters no longer take contracts on you. "
				+ "Collectors do.", Misc.getTextColor());
		FactionAPI indep = Global.getSector().getFaction(Factions.INDEPENDENT);
		if (indep != null) msg.setIcon(indep.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg);
	}

	// ------------------------------------------------------------------
	// (f)-(h): idempotent cleanup, every load
	// ------------------------------------------------------------------

	/**
	 * (f) Evict every deprecated PersonalBountyIntel.
	 *
	 * <p>Two-phase collect-then-remove, because removeIntel mutates the list
	 * getIntel() hands back. The no-arg getIntel() plus instanceof is used
	 * rather than getIntel(Class), whose subclass-matching semantics are not
	 * documented.
	 *
	 * <p>The final unset is MANDATORY, not tidiness. The old implementation
	 * pinned the intel in sector memory as well as registering it, so
	 * removeIntel alone leaves a live reference and XStream keeps writing the
	 * object into every subsequent save - forever.
	 */
	private static void purgeBountyIntel(SectorAPI sector) {
		List<IntelInfoPlugin> stale = new ArrayList<IntelInfoPlugin>();
		for (IntelInfoPlugin p : sector.getIntelManager().getIntel()) {
			if (p instanceof PersonalBountyIntel) stale.add(p);
		}
		for (IntelInfoPlugin p : stale) {
			((PersonalBountyIntel) p).endImmediately();
			sector.getIntelManager().removeIntel(p);
		}

		sector.getMemoryWithoutUpdate().unset(PersonalBountyIntel.KEY);

		if (!stale.isEmpty()) {
			log.info("Removed " + stale.size() + " deprecated PersonalBountyIntel entries");
		}
	}

	/**
	 * (g) Drop the deprecated BountyHunterManager out of the script list.
	 * instanceof rather than an exact class match, so a subclass restored from
	 * a save is caught too.
	 */
	private static void purgeBountyHunterManager(SectorAPI sector) {
		for (EveryFrameScript s : new ArrayList<EveryFrameScript>(sector.getScripts())) {
			if (s instanceof BountyHunterManager) {
				sector.removeScript(s);
				log.info("Removed deprecated BountyHunterManager");
			}
		}
	}

	/**
	 * (h) Legacy hunter fleets are stood down and sent home - not converted,
	 * not despawned. Converting them would preserve exactly the doomstack this
	 * rework exists to kill (their size was derived from a number in credits
	 * and never looked at the player), and despawning makes fleets vanish from
	 * under a player who is mid-chase.
	 *
	 * <p>$piratepat_hunter is the only handle: it was set on every hunter at
	 * spawn and read nowhere. Unsetting it in the same pass makes this method
	 * a no-op on every subsequent load, which matters because it adds an
	 * AutoDespawnScript.
	 *
	 * <p>Clearing the hostility is subtler than it looks. The old code set the
	 * pursuit flag with its own reason string, but hostility and aggression
	 * came from FleetCreatorMission.triggerMakeHostileAndAggressive(), which
	 * uses a reason string of its own. Misc.setFlagWithReason(..., false, ...)
	 * only unsets the "&lt;flag&gt;_&lt;reason&gt;" key it is given, so
	 * unsetting by OUR reason alone would leave these fleets permanently
	 * hostile. Misc.clearFlag drops every registered reason, and the bare keys
	 * are unset outright afterwards - the same belt-and-braces vanilla itself
	 * uses in MakeOtherFleetNonHostile's generic branch.
	 */
	private static void standDownLegacyHunters(SectorAPI sector) {
		List<CampaignFleetAPI> hunters = new ArrayList<CampaignFleetAPI>();
		for (LocationAPI loc : sector.getAllLocations()) {
			if (loc == null) continue;
			for (CampaignFleetAPI fleet : loc.getFleets()) {
				if (fleet == null) continue;
				if (fleet.getMemoryWithoutUpdate().getBoolean(BountyHunterManager.HUNTER_FLAG)) {
					hunters.add(fleet);
				}
			}
		}
		if (hunters.isEmpty()) return;

		for (CampaignFleetAPI fleet : hunters) {
			MemoryAPI mem = fleet.getMemoryWithoutUpdate();
			mem.unset(BountyHunterManager.HUNTER_FLAG);

			Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_PURSUE_PLAYER,
					OLD_FLAG_REASON, false, 0f);
			Misc.clearFlag(mem, MemFlags.MEMORY_KEY_PURSUE_PLAYER);
			mem.unset(MemFlags.MEMORY_KEY_PURSUE_PLAYER);

			Misc.clearFlag(mem, MemFlags.MEMORY_KEY_MAKE_HOSTILE);
			mem.unset(MemFlags.MEMORY_KEY_MAKE_HOSTILE);
			mem.unset(MemFlags.MEMORY_KEY_MAKE_HOSTILE + "_" + Factions.PLAYER);

			Misc.clearFlag(mem, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
			mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE);
			mem.unset(MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE_ONE_BATTLE_ONLY);
			mem.unset(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT);

			// the AI caches its target, so it has to be told to reconsider or
			// a hunter already locked onto the player keeps closing
			if (fleet.getAI() instanceof ModularFleetAIAPI) {
				ModularFleetAIAPI ai = (ModularFleetAIAPI) fleet.getAI();
				if (ai.getTacticalModule() != null) {
					ai.getTacticalModule().setTarget(null);
					ai.getTacticalModule().forceTargetReEval();
				}
			}

			// the old despawn script was constructed with a null mission
			fleet.removeScriptsOfClass(MissionFleetAutoDespawn.class);
			// withClear TRUE - we WANT the old INTERCEPT gone
			Misc.giveStandardReturnToSourceAssignments(fleet, true);
			fleet.addScript(new AutoDespawnScript(fleet));
		}

		log.info("Stood down " + hunters.size() + " legacy bounty hunter fleet(s)");

		MessageIntel msg = new MessageIntel();
		msg.addLine("The hunters break off", Misc.getPositiveHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "They turn for home. Word is your paper has "
				+ "changed hands.", Misc.getTextColor());
		FactionAPI indep = Global.getSector().getFaction(Factions.INDEPENDENT);
		if (indep != null) msg.setIcon(indep.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg);
	}
}
