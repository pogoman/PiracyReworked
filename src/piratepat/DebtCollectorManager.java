package piratepat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.apache.log4j.Logger;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FactionAPI.ShipPickMode;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI.EncounterOption;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.MutableStat.StatMod;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.AutoDespawnScript;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * Dispatches the debt collectors. Replaces BountyHunterManager, which sized
 * its fleets off a number in credits and never once looked at the player -
 * that is how a 161-FP doomstack ended up hunting two frigates.
 *
 * <p>Everything this class does is driven by PirateDebt. It owns no state
 * about the debt itself, holds no fleet references, and re-derives the world
 * every tick:
 *
 * <ul>
 * <li><b>The notice.</b> PirateDebt.isNoticeDue() then sendCollectionNotice().
 *     Critique F3: that notice carries NO MessageClickAction, and the whole
 *     of it lives in PirateDebt - the manager only asks.</li>
 * <li><b>Rediscovery.</b> Live collectors are found by scanning
 *     getAllLocations() for $piratepat_collector. A CampaignFleetAPI is never
 *     stored in a field, so nothing this class holds can enter a save.</li>
 * <li><b>Pursuit.</b> $pursuePlayer and $keepPursuingPlayer are refreshed on a
 *     ONE-DAY expiry while the player is visible - the HasslePlayerScript
 *     idiom. The old code set them for sixty days at spawn, which made
 *     pursuit literally unshakeable. A day out of sensor range now loses
 *     them.</li>
 * <li><b>The burn boost.</b> Re-applied every maintenance tick rather than
 *     once at spawn, which sidesteps the unverified question of whether a
 *     fleetwide burn stat modifier survives serialisation, and lets the boost
 *     track the player's own burn instead of being a permanent flat bonus.</li>
 * <li><b>One fleet.</b> There is never more than one live collector. The
 *     sweep counts what is flying and nothing is dispatched while that count
 *     is non-zero - not a top-up, not an escalation, not a second crew for
 *     a bigger balance. A replacement goes out only once the first is gone:
 *     home with its hunt expired, or dead to somebody who was not the
 *     player. Dead to the player, there is no debt left to send it for -
 *     see the officer rule in PirateDebt.reportCollectorEliminated.</li>
 * <li><b>Sizing.</b> The whole formula lives in PirateDebt
 *     (collectorFP / collectorMaxShipSize), including binding fix F1's
 *     floor = min(debtCollectorMinFP, playerCombatFP). The one fleet is sent
 *     at full strength from the first day.</li>
 * <li><b>Author decision D2.</b> A pirate-held debt sends GENUINE pirate
 *     fleets, and they fight a pirate-allied player. See applyHostility().</li>
 * </ul>
 *
 * <p>Two intervals. A fast one (fractions of a day) refreshes pursuit and
 * catches the notice on the day it falls due; a slow one (8-12 days, halved
 * while enforcing) does the full-sector sweep and rolls for a spawn. The slow
 * roll is the only expensive thing here and it happens roughly three times a
 * campaign month.
 */
public class DebtCollectorManager implements EveryFrameScript {

	public static Logger log = Global.getLogger(DebtCollectorManager.class);

	/**
	 * Stat modifier id for the burn boost. Distinct from PirateDebt.FLAG_REASON
	 * because it is a stat source, not a memory-flag reason, and because this
	 * class removes it itself on stand-down.
	 */
	public static final String BURN_MOD_ID = "piratepat_debtCollector";

	/** The defeat trigger registered on every collector AT SPAWN - see below. */
	public static final String DEFEAT_TRIGGER = "PiratepatCollectorBeaten";

	/** Days between spawn rolls, before the enforcement halving. */
	public static final float SPAWN_CHECK_MIN = 8f;
	public static final float SPAWN_CHECK_MAX = 12f;

	/** Days between maintenance ticks. Cheap - it only walks one location. */
	public static final float MAINTAIN_CHECK_MIN = 0.2f;
	public static final float MAINTAIN_CHECK_MAX = 0.4f;

	/**
	 * Pursuit flags are set for exactly this long and renewed while the player
	 * is visible, so losing the collector for a day loses the pursuit.
	 */
	public static final float PURSUE_EXPIRE_DAYS = 1f;

	/** How far from the player a collector drops out of the black. */
	public static final float SPAWN_DIST_MIN = 1200f;
	public static final float SPAWN_DIST_MAX = 2500f;

	/** Fuel-carrying tail, as a fraction of the combat budget. */
	public static final float TANKER_FRACTION = 0.1f;

	/**
	 * Quality bonus for a house-held collector. The collection house hires
	 * professionals; the pirates who buy the paper do not, and their own
	 * doctrine (shipQuality 1 against the mercenary 5) does that work for us.
	 */
	public static final float HOUSE_QUALITY_MOD = 0.25f;

	protected IntervalUtil spawnInterval = new IntervalUtil(SPAWN_CHECK_MIN, SPAWN_CHECK_MAX);
	protected IntervalUtil maintainInterval = new IntervalUtil(MAINTAIN_CHECK_MIN, MAINTAIN_CHECK_MAX);
	protected Random random = new Random();

	/** XStream skips constructors and field initializers - guard every field. */
	protected Object readResolve() {
		if (spawnInterval == null) {
			spawnInterval = new IntervalUtil(SPAWN_CHECK_MIN, SPAWN_CHECK_MAX);
		}
		if (maintainInterval == null) {
			maintainInterval = new IntervalUtil(MAINTAIN_CHECK_MIN, MAINTAIN_CHECK_MAX);
		}
		if (random == null) random = new Random();
		return this;
	}

	// ------------------------------------------------------------------
	// the tick
	// ------------------------------------------------------------------

	public void advance(float amount) {
		SectorAPI sector = Global.getSector();
		if (sector == null) return;
		float days = sector.getClock().convertToDays(amount);

		maintainInterval.advance(days);
		boolean maintain = maintainInterval.intervalElapsed();

		// enforcement halves the spawn interval by advancing it twice as fast,
		// which needs no second IntervalUtil and no state
		spawnInterval.advance(PirateDebt.isEnforcing() ? days * 2f : days);
		boolean sweep = spawnInterval.intervalElapsed();

		if (!maintain && !sweep) return;

		// the mod or the debt being switched off mid-campaign must not leave
		// hostile fleets flying at the player: the fast tick stands down
		// anything in the player's own system within a fraction of a day
		// (maintainNearbyCollectors retires everything once isLive is false)
		// and the slow one finds the rest of the sector. Nothing accrues,
		// nothing is billed, and the balance waits - see PirateDebt.KEY_DORMANT.
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) {
			PirateDebt.markDormant();
			if (maintain) maintainNearbyCollectors();
			if (sweep) sweepLocations(true);
			return;
		}
		PirateDebt.wakeIfDormant();

		if (maintain) {
			if (PirateDebt.isNoticeDue()) PirateDebt.sendCollectionNotice();
			maintainNearbyCollectors();
		}

		if (sweep) sweepAndSpawn();
	}

	/**
	 * Stand every collector in the sector down right now and mark the balance
	 * dormant. Called on game load and from the LunaLib settings hook when the
	 * debt system (or the mod) is found switched off. The tick would get there
	 * on its own - but a crew already burning toward the player should not be
	 * given the chance to arrive first.
	 */
	public void retireAll() {
		PirateDebt.markDormant();
		sweepLocations(true);
		log.info("Debt system switched off: every collector stood down");
	}

	/** The debt system is on again: restart a dormant balance's clocks, if any. */
	public void wake() {
		PirateDebt.wakeIfDormant();
	}

	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return false;
	}

	// ------------------------------------------------------------------
	// maintenance
	// ------------------------------------------------------------------

	/**
	 * The fast tick. Only walks the player's own location, because a collector
	 * anywhere else is neither pursuing nor about to be seen, and a full
	 * sector scan several times a day would be wasteful.
	 */
	protected void maintainNearbyCollectors() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || player.getContainingLocation() == null) return;

		boolean keepHunting = PirateDebt.isLive() && !PirateDebt.hasPlan();
		String holderId = PirateDebt.collectorFactionId();

		for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(
				player.getContainingLocation().getFleets())) {
			if (fleet == null || fleet == player) continue;
			if (!fleet.getMemoryWithoutUpdate().getBoolean(PirateDebt.COLLECTOR_KEY)) continue;

			if (!keepHunting || shouldRetire(fleet, holderId)) {
				standDown(fleet);
				continue;
			}
			refreshPursuit(fleet, player);
			refreshBurnBoost(fleet, player);
		}
	}

	/**
	 * A live collector that must be taken off the player: it has already been
	 * paid here, or the paper has changed hands and it is flying the wrong
	 * colours. The colours test is how the sale reaches fleets already in
	 * space - the house's people go home and the pirates send their own.
	 */
	protected boolean shouldRetire(CampaignFleetAPI collector, String holderId) {
		MemoryAPI mem = collector.getMemoryWithoutUpdate();
		if (mem.getBoolean(PirateDebt.SETTLED_KEY)) return true;
		FactionAPI faction = collector.getFaction();
		if (faction == null || holderId == null) return false;
		return !holderId.equals(faction.getId());
	}

	/**
	 * Renew the flags that keep a collector on the player's tail. Short
	 * expiries, renewed while the player is visible to the collector - the
	 * HasslePlayerScript idiom. FLEET_BUSY and FLEET_SPECIAL_ACTION stop a
	 * military response script co-opting the fleet away from the contract.
	 */
	protected void refreshPursuit(CampaignFleetAPI collector, CampaignFleetAPI player) {
		MemoryAPI mem = collector.getMemoryWithoutUpdate();
		Misc.setFlagWithReason(mem, MemFlags.FLEET_BUSY, PirateDebt.FLAG_REASON, true, -1f);
		mem.set(MemFlags.FLEET_SPECIAL_ACTION, true);

		// FID.winningPath removes a defeat trigger once it has fired, and a
		// crew that lost a battle with an officer still alive fights again.
		// The wonFight handler re-arms it on the spot; this is the backstop
		// for a collector that reached the player through some other path.
		ensureDefeatTrigger(collector);

		if (player.getVisibilityLevelTo(collector) == VisibilityLevel.NONE) return;

		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_PURSUE_PLAYER,
				PirateDebt.FLAG_REASON, true, PURSUE_EXPIRE_DAYS);
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_STICK_WITH_PLAYER_IF_ALREADY_TARGET,
				PirateDebt.FLAG_REASON, true, PURSUE_EXPIRE_DAYS);
	}

	/**
	 * The defeat trigger, present EXACTLY once: added if missing, collapsed
	 * if duplicated. wonFight adds a raw second copy while the FID is still
	 * holding the first, because the FID removes one copy after the fire -
	 * this makes the list self-healing whichever way that goes.
	 */
	public static void ensureDefeatTrigger(CampaignFleetAPI collector) {
		if (collector == null) return;
		List<String> triggers = Misc.getDefeatTriggers(collector, false);
		int copies = 0;
		if (triggers != null) {
			for (String trigger : triggers) {
				if (DEFEAT_TRIGGER.equals(trigger)) copies++;
			}
		}
		if (copies == 0) {
			Misc.addDefeatTrigger(collector, DEFEAT_TRIGGER);
			return;
		}
		while (copies > 1) {
			triggers.remove(DEFEAT_TRIGGER);
			copies--;
		}
	}

	/**
	 * Give the collector just enough burn to close the gap, capped at
	 * debtCollectorBurnBoost. Re-applied every maintenance tick rather than
	 * set once at spawn: whether a fleetwide burn modifier survives save and
	 * reload is unverified, and re-applying makes the question moot.
	 *
	 * <p>modifyFlat is a no-op when the value has not changed, so renewing
	 * this several times a day costs nothing.
	 */
	protected void refreshBurnBoost(CampaignFleetAPI collector, CampaignFleetAPI player) {
		StatBonus burn = collector.getStats().getFleetwideMaxBurnMod();
		StatMod mine = burn.getFlatBonus(BURN_MOD_ID);
		float applied = mine == null ? 0f : mine.value;

		int max = PiratePatConfig.debtCollectorBurnBoost();
		if (max <= 0) {
			if (applied != 0f) burn.unmodifyFlat(BURN_MOD_ID);
			return;
		}

		// getMinBurnLevel() already includes whatever we applied last time, so
		// subtract our own contribution to recover the fleet's own burn
		float unboosted = collector.getFleetData().getMinBurnLevel() - applied;
		float deficit = player.getFleetData().getMinBurnLevel() - unboosted;

		int boost = (int) Math.ceil(deficit);
		if (boost < 0) boost = 0;
		if (boost > max) boost = max;

		if (boost <= 0) {
			if (applied != 0f) burn.unmodifyFlat(BURN_MOD_ID);
			return;
		}
		burn.modifyFlat(BURN_MOD_ID, boost, "Working a collection contract");
	}

	/** Drop this class's burn modifier, if it put one on. */
	protected void clearBurnBoost(CampaignFleetAPI fleet) {
		StatBonus burn = fleet.getStats().getFleetwideMaxBurnMod();
		if (burn.getFlatBonus(BURN_MOD_ID) != null) burn.unmodifyFlat(BURN_MOD_ID);
	}

	/**
	 * Take a collector off the contract. There is exactly ONE stand-down in
	 * this mod and it is PirateDebt.markSettled - this method only cleans up
	 * the two things markSettled cannot know about (this class's burn
	 * modifier and its map marker) and then delegates.
	 *
	 * <p>Critique F8 is handled inside markSettled: it unsets
	 * $piratepat_collector as well as setting $piratepat_settled, so a fleet
	 * flying home stops counting against debtCollectorMaxFleets.
	 */
	protected void standDown(CampaignFleetAPI collector) {
		if (collector == null) return;
		clearBurnBoost(collector);
		Misc.makeUnimportant(collector, PirateDebt.FLAG_REASON);
		PirateDebt.markSettled(collector);
	}

	// ------------------------------------------------------------------
	// the sweep and the spawn roll
	// ------------------------------------------------------------------

	/**
	 * Walk every location and reconcile what is flying against what the debt
	 * currently says. Collectors that have been paid, that fly the previous
	 * holder's colours, or that are hunting a player who has signed terms are
	 * stood down here.
	 *
	 * @param retireEverything stand down every collector regardless, used when
	 *        the mod or the debt system has been switched off
	 * @return how many collectors are still hunting
	 */
	protected int sweepLocations(boolean retireEverything) {
		int live = 0;
		String holderId = PirateDebt.collectorFactionId();
		boolean keepHunting = !retireEverything
				&& PirateDebt.isLive() && !PirateDebt.hasPlan();

		for (LocationAPI loc : Global.getSector().getAllLocations()) {
			if (loc == null) continue;
			for (CampaignFleetAPI fleet : new ArrayList<CampaignFleetAPI>(loc.getFleets())) {
				if (fleet == null) continue;
				MemoryAPI mem = fleet.getMemoryWithoutUpdate();

				if (!mem.getBoolean(PirateDebt.COLLECTOR_KEY)) {
					// a fleet stood down by the dialog keeps our burn modifier
					// until somebody takes it off; this is that somebody
					if (mem.getBoolean(PirateDebt.SETTLED_KEY)) clearBurnBoost(fleet);
					continue;
				}

				if (!keepHunting || shouldRetire(fleet, holderId)) {
					standDown(fleet);
					continue;
				}
				live++;
			}
		}
		return live;
	}

	/**
	 * The slow tick. Reconciles the live collectors, then decides whether to
	 * send THE collector.
	 *
	 * <p>One fleet, full stop. While it is alive - hunting, limping away from
	 * a lost battle with an officer still aboard, or lost in another system -
	 * nobody else is dispatched. The old design spent its budget across up
	 * to three concurrent fleets and topped them up as they died; that is
	 * the ladder of baby fleets the officer rule replaces.
	 */
	protected void sweepAndSpawn() {
		int live = sweepLocations(false);

		if (!PirateDebt.canDispatchCollector()) return;
		if (live >= 1) return;

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || !player.isValidPlayerFleet()) return;
		if (player.getContainingLocation() == null) return;
		if (player.isInHyperspaceTransition()) return;

		float fp = PirateDebt.collectorFP();
		if (fp < 1f) return;

		float prob = PiratePatConfig.debtSpawnProb();
		if (PirateDebt.isEnforcing()) prob *= 2f;
		if (prob > 1f) prob = 1f;
		if (random.nextFloat() > prob) return;

		spawnCollector(player, fp);
	}

	// ------------------------------------------------------------------
	// spawning
	// ------------------------------------------------------------------

	/**
	 * Build and dispatch one collector.
	 *
	 * <p>The faction is PirateDebt.collectorFactionId() for BOTH the fleet's
	 * colours and its hull source, so a pirate-held debt sends real pirates
	 * (author decision D2) and a house-held one sends independents. It is not
	 * re-derived here - there is one source of truth for who owns the paper.
	 *
	 * <p>Binding fix F2: modeOverride is set explicitly. Without it
	 * FleetFactoryV3 reads the pick mode off whatever market it fabricates,
	 * and the whole FP analysis this rework is built on assumes
	 * PRIORITY_THEN_ALL.
	 *
	 * <p>DELIBERATE DEVIATION FROM THE SPEC: there is no DISENGAGE veto here.
	 * Vanilla's DeliveryFailureConsequences throws away a hunter whose AI
	 * would rather not fight, but it can afford to - it asks for 30 to 150 FP
	 * against whatever the player happens to be flying. Fix F1 pins a
	 * collector to a ratio of the player, so a veto would discard the fleet,
	 * re-roll the same figure on the next tick, and discard that one too:
	 * collectors would simply never appear. The one fleet is sent at the full
	 * ratio instead, which reads as strong enough to force the fight; the
	 * chosen encounter option is logged at spawn so that can be checked off
	 * the log rather than guessed at.
	 *
	 * <p>THE OFFICER RULE needs officers to count. withOfficers puts the
	 * commander on the flagship and hands out officers by doctrine, but a
	 * registered GenerateFleetOfficersPlugin can do anything it likes, so
	 * ensureOfficered() guarantees at least the flagship carries a real
	 * person - otherwise the first battle would end the debt whatever
	 * happened in it.
	 *
	 * @param player the fleet being collected from
	 * @param combatPts fleet points requested for this collector
	 * @return the fleet, or null if creation failed
	 */
	protected CampaignFleetAPI spawnCollector(CampaignFleetAPI player, float combatPts) {

		String factionId = PirateDebt.collectorFactionId();
		boolean pirateHeld = PirateDebt.isPirateHeld();
		int maxShipSize = PirateDebt.collectorMaxShipSize();

		FleetParamsV3 params = new FleetParamsV3(
				null,                                 // no source market
				player.getLocationInHyperspace(),
				factionId,                            // D2: real colours, real hulls
				null,                                 // quality off the picked market
				FleetTypes.MERC_BOUNTY_HUNTER,
				combatPts,                            // combatPts
				0f,                                   // freighterPts
				combatPts * TANKER_FRACTION,          // tankerPts
				0f,                                   // transportPts
				0f,                                   // linerPts
				0f,                                   // utilityPts
				pirateHeld ? 0f : HOUSE_QUALITY_MOD); // qualityMod
		params.random = random;
		params.modeOverride = ShipPickMode.PRIORITY_THEN_ALL;   // F2
		params.maxShipSize = maxShipSize;                       // the anti-doomstack guard
		params.withOfficers = true;

		CampaignFleetAPI fleet = FleetFactoryV3.createFleet(params);
		if (fleet == null || fleet.isEmpty()) {
			log.warn("Debt collector creation returned an empty fleet at " + (int) combatPts
					+ " FP for faction " + factionId);
			return null;
		}

		ensureOfficered(fleet);

		fleet.setName(pirateHeld ? "Collection Crew" : "Collection Fleet");
		// NOT MemFlags.MEMORY_KEY_PIRATE: TransponderAbilityAI switches the
		// transponder off for $isPirate fleets and the collector would read as
		// an unidentified contact, which is the exact opposite of D2
		fleet.setTransponderOn(true);

		MemoryAPI mem = fleet.getMemoryWithoutUpdate();
		mem.set(PirateDebt.COLLECTOR_KEY, true);
		// rolled ONCE, here, and read back with markupOn()/quotedBy() forever
		// after - rolling twice for one fleet would quote a different number
		// across a save and reload
		mem.set(PirateDebt.MARKUP_KEY, PirateDebt.rollMarkup());

		applyHostility(fleet);
		Misc.makeImportant(fleet, PirateDebt.FLAG_REASON);

		// registered AT SPAWN, never in the dialog's fight branch: a player who
		// cuts the comm link and engages from the fleet screen must still get
		// the officer rule narrated
		ensureDefeatTrigger(fleet);
		fleet.addEventListener(new DebtCollectorFleetListener());

		LocationAPI where = player.getContainingLocation();
		where.addEntity(fleet);
		Vector2f spawnAt = Misc.getPointWithinRadiusUniform(player.getLocation(),
				SPAWN_DIST_MIN, SPAWN_DIST_MAX, random);
		fleet.setLocation(spawnAt.x, spawnAt.y);

		float huntDays = PiratePatConfig.debtCollectorHuntDays();
		fleet.addAssignment(FleetAssignment.INTERCEPT, player, huntDays, "collecting on a debt");
		// withClear FALSE: the intercept stays first in the queue and the trip
		// home is appended behind it
		Misc.giveStandardReturnToSourceAssignments(fleet, false);
		fleet.addScript(new AutoDespawnScript(fleet));

		refreshPursuit(fleet, player);
		refreshBurnBoost(fleet, player);

		if (PiratePatConfig.debugLogging()) logSpawn(fleet, player, combatPts);
		return fleet;
	}

	/**
	 * Guarantee the officer rule has something to count. FleetFactoryV3 with
	 * withOfficers always puts the commander on the flagship, but a
	 * GenerateFleetOfficersPlugin from another mod replaces that step
	 * wholesale and may leave every captain default - and a collector with
	 * no officers would satisfy "every officer is dead" before a shot was
	 * fired. Only ever adds; never touches a fleet that already has one.
	 */
	protected void ensureOfficered(CampaignFleetAPI fleet) {
		if (PirateDebt.officersRemaining(fleet) > 0) return;

		fleet.getFleetData().ensureHasFlagship();
		FleetMemberAPI flagship = fleet.getFlagship();
		if (flagship == null) {
			for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
				if (member != null && !member.isFighterWing()) {
					flagship = member;
					break;
				}
			}
		}
		if (flagship == null) return;

		PersonAPI commander = fleet.getCommander();
		if (commander == null || commander.isDefault()) {
			commander = fleet.getFaction().createRandomPerson(random);
			commander.setRankId(Ranks.SPACE_COMMANDER);
			commander.setPostId(Ranks.POST_FLEET_COMMANDER);
			fleet.setCommander(commander);
		}
		fleet.getFleetData().setFlagship(flagship);
		flagship.setCaptain(commander);
		log.warn("Debt collector spawned with no officers aboard; put the commander on "
				+ flagship.getShipName());
	}

	/**
	 * Author decision D2, in full. A pirate-held debt sends pirate-faction
	 * fleets, and they must fight a player who may be COOPERATIVE with the
	 * pirates - which is the whole premise of this mod.
	 *
	 * <p>That works because per-fleet hostility is checked BEFORE faction
	 * relations: CampaignFleetAPI.isHostileTo only "eventually falls back to
	 * faction.isHostile()", so $cfai_makeHostile outranks FRIENDLY and
	 * COOPERATIVE alike. Vanilla relies on the same thing in
	 * DeliveryFailureConsequences, which sends real pirates after the player
	 * with no check at all on pirate standing.
	 *
	 * <p>Three things do NOT follow the memory flag and would bite:
	 * <ol>
	 * <li>The "these people are not currently hostile, are you sure?"
	 *     confirmation reads the raw faction relationship.</li>
	 * <li>Winning applies RepActions.COMBAT_NORMAL, whose ensureAtBest is
	 *     RepLevel.HOSTILE - one won fight would drop a COOPERATIVE patron
	 *     straight to hostile with the pirates.</li>
	 * <li>A friendly pirate patrol pulled onto the collector's side can become
	 *     the battle's primary non-player fleet.</li>
	 * </ol>
	 * makeNoRepImpact closes the first two at once: NO_REP_IMPACT nulls the
	 * vanilla rep action entirely, and either rep-impact flag suppresses the
	 * confirmation. PirateDebt.applyCollectorKillRep then charges the mod's
	 * own configurable price instead. FLEET_IGNORED_BY_OTHER_FLEETS is half
	 * the answer to the third; the other half belongs to the
	 * BeginFleetEncounter rule command, which must propagate makeNoRepImpact
	 * across the non-player side of the battle.
	 *
	 * <p>Every flag that must come off again is set through
	 * PirateDebt.FLAG_REASON, because PirateDebt.markSettled lifts exactly
	 * that set in one pass.
	 */
	protected void applyHostility(CampaignFleetAPI fleet) {
		MemoryAPI mem = fleet.getMemoryWithoutUpdate();

		// anything that would outrank the forced hostility goes first.
		// MAKE_HOSTILE normally beats MAKE_NON_HOSTILE, but a fleet carrying
		// $makeNonHostileTakesPriority would win
		Misc.clearFlag(mem, MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE + "_" + Factions.PLAYER);
		mem.unset(MemFlags.NON_HOSTILE_OVERRIDES_MAKE_HOSTILE);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_ALLOW_DISENGAGE);

		// hostile to the player, both keys. The bare key IS the
		// hostile-to-player key - Misc.isFleetMadeHostileToFaction special
		// cases Factions.PLAYER on it - and the suffixed one is what
		// Nexerelin's police raids set alongside it against a friendly faction
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_HOSTILE,
				PirateDebt.FLAG_REASON, true, -1f);
		Misc.makeHostileToFaction(fleet, Factions.PLAYER, true, -1f);

		// "aggressive" is not hostility - it means "always engage IF already
		// hostile". Permanent, not one battle only: a collector that survives
		// an exchange comes straight back
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE,
				PirateDebt.FLAG_REASON, true, -1f);
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_PREVENT_DISENGAGE,
				PirateDebt.FLAG_REASON, true, -1f);

		// reach the player and stay on them
		mem.set(MemFlags.MEMORY_KEY_ALLOW_LONG_PURSUIT, true);
		mem.set(MemFlags.FLEET_DO_NOT_IGNORE_PLAYER, true);
		mem.set(MemFlags.MEMORY_KEY_FLEET_DO_NOT_GET_SIDETRACKED, true);

		// keep friendly patrols out of it, and keep response scripts from
		// co-opting the collector away from the contract
		mem.set(MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, true);
		Misc.setFlagWithReason(mem, MemFlags.FLEET_BUSY, PirateDebt.FLAG_REASON, true, -1f);
		mem.set(MemFlags.FLEET_SPECIAL_ACTION, true);

		// no vanilla reputation from this fight, ever - and no confirmation
		// popup either, since both are gated on these two flags
		Misc.makeNoRepImpact(fleet, PirateDebt.FLAG_REASON);

		mem.set(MemFlags.MEMORY_KEY_SAW_PLAYER_WITH_TRANSPONDER_ON, true);
	}

	// ------------------------------------------------------------------
	// diagnostics
	// ------------------------------------------------------------------

	/**
	 * Proves the anti-doomstack formula in the log: what was asked for against
	 * what FleetFactoryV3 actually built, next to the player it was sized
	 * against.
	 *
	 * <p>Two residuals worth reading in this line. FleetFactoryV3 rewrites any
	 * non-zero combat request below 10 FP as 5 + random(6), so the bottom of
	 * the curve always comes back larger than requested - maxShipSize is what
	 * keeps that honest, and a frigate player never sees a cruiser whatever
	 * the FP says. And a registered CreateFleetPlugin (Nexerelin ships one)
	 * can intercept createFleet entirely, in which case none of the FP
	 * guarantees hold and this line is the only place it will show.
	 */
	protected void logSpawn(CampaignFleetAPI fleet, CampaignFleetAPI player, float requested) {

		int actual = FleetFactoryV3.getFP(fleet);
		int playerFP = PirateDebt.playerCombatFP();
		float ratio = playerFP > 0 ? actual / (float) playerFP : 0f;

		String option = "unknown";
		CampaignFleetAIAPI ai = fleet.getAI();
		if (ai != null) {
			EncounterOption pick = ai.pickEncounterOption(null, player);
			if (pick != null) option = pick.name();
		}

		log.info("Debt collector dispatched"
				+ " | faction " + PirateDebt.collectorFactionId()
				+ " | requested " + (int) requested + " FP"
				+ " | actual " + actual + " FP"
				+ " | player combat " + playerFP + " FP"
				+ " | collector/player " + Math.round(ratio * 100f) / 100f + "x"
				+ " | ratio " + PiratePatConfig.debtCollectorFPRatio()
				+ " | maxShipSize " + PirateDebt.collectorMaxShipSize()
				+ " | ships " + fleet.getFleetData().getMembersListCopy().size()
				+ " | officers " + PirateDebt.officersRemaining(fleet)
				+ " | markup " + PirateDebt.markupOn(fleet)
				+ " | balance " + (int) PirateDebt.principal()
				+ " | encounter option " + option);

		List<String> triggers = Misc.getDefeatTriggers(fleet, false);
		if (triggers == null || !triggers.contains(DEFEAT_TRIGGER)) {
			log.warn("Debt collector spawned WITHOUT the " + DEFEAT_TRIGGER
					+ " defeat trigger - the officer rule will only reach the message log");
		}
	}
}
