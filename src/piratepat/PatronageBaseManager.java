package piratepat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.impl.campaign.command.WarSimScript;
import com.fs.starfarer.api.impl.campaign.econ.RecentUnrest;
import com.fs.starfarer.api.impl.campaign.econ.ShippingDisruption;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel.PirateBaseTier;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseManager;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * Replaces the vanilla PirateBaseManager with the war chest economy. Extends
 * it (and takes over its memory key) so every vanilla code path that consults
 * PirateBaseManager.getInstance() - base tier tables, raid grace period,
 * Tuning.getDaysSinceStart() - keeps working unchanged.
 *
 * Differences from vanilla:
 * - Bases are PURCHASED from the war chest (cost rises per operating base)
 *   instead of maintained at a fixed 2-3 count. No chest, no new bases.
 * - Destroying a base does NOT make future bases spawn at higher tiers
 *   (vanilla's numDestroyed * 200 days rule is dropped); the vanilla 6-18
 *   month respawn freeze after a kill is kept.
 * - Base defense fleets scale with base tier (vanilla keeps 2 light + 1
 *   medium patrols at every tier).
 */
public class PatronageBaseManager extends PirateBaseManager {

	public static Logger log = Global.getLogger(PatronageBaseManager.class);

	public static final String DEFENSE_MOD_ID = "piratepat_def";

	protected IntervalUtil econInterval = new IntervalUtil(25f, 35f);
	protected IntervalUtil spawnInterval = new IntervalUtil(CHECK_DAYS * 0.75f, CHECK_DAYS * 1.25f);
	protected IntervalUtil plunderInterval = new IntervalUtil(4f, 6f);

	// transient: rebuilt each load. Tracks disruption timeLeft per market to
	// detect fresh disruptions; baselined suppresses crediting on the first
	// poll after a load (so pre-existing disruptions aren't re-counted).
	protected transient Map<String, Float> lastDisruptionSeen;
	protected transient boolean plunderBaselined;

	/**
	 * XStream skips constructors and field initializers when loading a save,
	 * so any field added after a save was created comes back null. Guard
	 * everything; also replicates the parent classes' readResolve guards,
	 * since defining this here shadows theirs.
	 */
	protected Object readResolve() {
		if (randomBase == null) randomBase = new java.util.Random();
		if (random == null) random = new java.util.Random();
		if (econInterval == null) econInterval = new IntervalUtil(25f, 35f);
		if (spawnInterval == null) spawnInterval = new IntervalUtil(CHECK_DAYS * 0.75f, CHECK_DAYS * 1.25f);
		if (plunderInterval == null) plunderInterval = new IntervalUtil(4f, 6f);
		if (trackedRaids == null) trackedRaids = new ArrayList<RaidIntel>();
		lastDisruptionSeen = new HashMap<String, Float>();
		plunderBaselined = false;
		return this;
	}

	public static PatronageBaseManager get() {
		PirateBaseManager instance = PirateBaseManager.getInstance();
		if (instance instanceof PatronageBaseManager) return (PatronageBaseManager) instance;
		return null;
	}

	@Override
	protected int getMinConcurrent() {
		return 0;
	}

	@Override
	protected int getMaxConcurrent() {
		return PiratePatConfig.maxBases();
	}

	@Override
	public void advance(float amount) {
		float days = Global.getSector().getClock().convertToDays(amount);

		// advance the bases we manage; drop the dead ones
		List<EveryFrameScript> remove = new ArrayList<EveryFrameScript>();
		for (EveryFrameScript event : active) {
			event.advance(amount);
			if (event.isDone()) remove.add(event);
		}
		for (EveryFrameScript event : remove) {
			if (event instanceof PirateBaseIntel && PiratePatConfig.enabled()) {
				PirateBaseIntel base = (PirateBaseIntel) event;
				String systemName = base.getSystem() != null
						? base.getSystem().getNameWithLowercaseTypeShort() : "unknown system";
				PiratePatData.reportBaseDestroyed(systemName);
			}
		}
		active.removeAll(remove);

		econInterval.advance(days);
		if (econInterval.intervalElapsed()) {
			if (PiratePatConfig.enabled()) {
				float income = PiratePatConfig.incomePerBasePerMonth() * getActiveCount();
				PiratePatData.addPassiveIncome(income);
				PiratePatData.decayNetTrade();
				PiratePatData.flushPlunderLedger();
			}
			applyDefenseScaling();
		}

		plunderInterval.advance(days);
		if (plunderInterval.intervalElapsed()) {
			if (PiratePatConfig.enabled() && PiratePatConfig.plunderEnabled()) {
				pollShippingDisruptions();
			}
		}

		spawnInterval.advance(days);
		if (spawnInterval.intervalElapsed()) {
			if (PiratePatConfig.enabled()) {
				trackAdoptedBaseRaids();
				checkBasePurchase();
			}
		}
	}

	protected List<RaidIntel> trackedRaids = new ArrayList<RaidIntel>();

	/**
	 * Credits spoils for a successful raid and sends a notification that
	 * spells out the consequences: what flowed into the war chest, and what
	 * the raid did to each raided colony (the recent-unrest stability hit,
	 * read directly off the markets moments after the raid resolves).
	 */
	public static void settleSuccessfulRaid(StarSystemAPI target, FactionAPI pirateFaction, float fp) {
		float spoils = fp * PiratePatConfig.raidCostPerFP() * PiratePatConfig.raidReturnCostFraction();
		String targetName = "hostile worlds";
		List<MarketAPI> raided = new ArrayList<MarketAPI>();
		if (target != null) {
			targetName = target.getNameWithNoType();
			for (MarketAPI curr : Misc.getMarketsInLocation(target)) {
				if (pirateFaction != null && curr.getFaction().isHostileTo(pirateFaction)) {
					raided.add(curr);
					spoils += curr.getSize() * PiratePatConfig.raidReturnPerMarketSize();
				}
			}
		}
		PiratePatData.addRaidReturn(spoils, targetName);

		MessageIntel msg = new MessageIntel();
		msg.addLine("Pirate raid profits flow to the war chest", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Spoils from " + targetName + ": %s",
				Misc.getTextColor(),
				new String[] { "+" + Misc.getDGSCredits(spoils) },
				Misc.getHighlightColor());
		for (MarketAPI market : raided) {
			int penalty = RecentUnrest.getPenalty(market);
			if (penalty > 0) {
				msg.addLine(BaseIntelPlugin.BULLET + market.getName() + ": stability %s",
						Misc.getTextColor(),
						new String[] { "-" + penalty },
						Misc.getNegativeHighlightColor());
			} else {
				msg.addLine(BaseIntelPlugin.BULLET + market.getName() + " raided", Misc.getTextColor());
			}
		}
		if (pirateFaction != null) msg.setIcon(pirateFaction.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg, MessageClickAction.INTEL_TAB);
	}

	/**
	 * PatronageBaseIntel handles its own raid ledger via overrides, but
	 * adopted vanilla bases launch raids through unmodified vanilla code.
	 * Catch those raids here: debit what the chest can cover at launch
	 * (vanilla bases can't be stopped by poverty - they're grandfathered),
	 * credit spoils on success, count defeats.
	 */
	protected void trackAdoptedBaseRaids() {
		List<MarketAPI> adoptedMarkets = new ArrayList<MarketAPI>();
		for (PirateBaseIntel base : getBases()) {
			if (base instanceof PatronageBaseIntel) continue;
			if (base.getMarket() != null) adoptedMarkets.add(base.getMarket());
		}

		if (!adoptedMarkets.isEmpty()) {
			for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel(RaidIntel.class)) {
				RaidIntel raid = (RaidIntel) intel;
				if (trackedRaids.contains(raid)) continue;
				if (raid.isEnding() || raid.isEnded()) continue;
				if (raid.getAssembleStage() == null) continue;

				boolean ours = false;
				for (MarketAPI source : raid.getAssembleStage().getSources()) {
					if (adoptedMarkets.contains(source)) {
						ours = true;
						break;
					}
				}
				if (!ours) continue;

				float cost = raid.getAssembleStage().getOrigSpawnFP() * PiratePatConfig.raidCostPerFP();
				PiratePatData.spendUpTo(cost, "Raiders draw on the war chest");
				PiratePatData.incrRaidsLaunched();
				trackedRaids.add(raid);
			}
		}

		Iterator<RaidIntel> iter = trackedRaids.iterator();
		while (iter.hasNext()) {
			RaidIntel raid = iter.next();
			if (!raid.isEnding() && !raid.isEnded()) continue;
			iter.remove();

			if (raid.isSucceeded()) {
				float fp = raid.getAssembleStage() != null
						? raid.getAssembleStage().getOrigSpawnFP() : 0f;
				settleSuccessfulRaid(raid.getSystem(), raid.getFaction(), fp);
			} else if (raid.isFailed()) {
				PiratePatData.incrRaidsDefeated();
			}
		}
	}

	protected void checkBasePurchase() {
		// vanilla's post-base-destruction freeze
		if (numSpawnChecksToSkip > 0) {
			numSpawnChecksToSkip--;
			return;
		}
		// vanilla skips half its spawn checks; keeps timing organic
		if (random.nextFloat() < CHECK_PROB) return;

		if (getActiveCount() >= PiratePatConfig.maxBases()) return;

		float cost = getCurrentBaseCost();
		if (PiratePatData.getChest() < cost) return;

		StarSystemAPI system = pickSystemForPirateBase();
		if (system == null) return;

		String factionId = pickPirateFaction();
		if (factionId == null) return;

		PatronageBaseIntel intel = new PatronageBaseIntel(system, factionId, pickTier());
		if (intel.isDone() || intel.isEnding()) return;

		PiratePatData.trySpend(cost, "The underworld commissions a new base of operations");
		PiratePatData.incrBasesPurchased();
		addActive(intel);

		if (PiratePatConfig.debugLogging()) {
			log.info("Purchased pirate base in " + system.getName() + " for " + (int) cost
					+ ", tier " + intel.getTier());
		}
	}

	/**
	 * Approximate months until the post-destruction respawn freeze lifts.
	 * Checks tick every ~10 game-days, so months ~= checks * 10 / 30.
	 */
	public int getSpawnFreezeMonthsRemaining() {
		if (numSpawnChecksToSkip <= 0) return 0;
		return Math.max(1, Math.round(numSpawnChecksToSkip * CHECK_DAYS / 30f));
	}

	/** Cost of the pirates' next base: baseCost * growth^(operating bases). */
	public float getCurrentBaseCost() {
		return PiratePatConfig.baseCost()
				* (float) Math.pow(PiratePatConfig.baseCostGrowth(), getActiveCount());
	}

	/**
	 * Vanilla tier table by campaign time, WITHOUT the vanilla rule that adds
	 * 200 days per destroyed base - in this economy, killing a base destroys
	 * the pirates' investment rather than teaching them to build better ones.
	 *
	 * Tier reflects WEALTH, not campaign time: a flush underworld builds a
	 * strong base immediately, a poor one builds a wreck. Measured as the
	 * chest's worth in base-costs, so it auto-scales with the cost config.
	 */
	@Override
	protected PirateBaseTier pickTier() {
		float ratio = PiratePatData.getChest() / Math.max(1f, PiratePatConfig.baseCost());

		WeightedRandomPicker<PirateBaseTier> picker = new WeightedRandomPicker<PirateBaseTier>(random);
		if (ratio < 1f) {
			picker.add(PirateBaseTier.TIER_1_1MODULE, 10f);
			picker.add(PirateBaseTier.TIER_2_1MODULE, 6f);
		} else if (ratio < 2f) {
			picker.add(PirateBaseTier.TIER_2_1MODULE, 10f);
			picker.add(PirateBaseTier.TIER_3_2MODULE, 6f);
		} else if (ratio < 4f) {
			picker.add(PirateBaseTier.TIER_3_2MODULE, 10f);
			picker.add(PirateBaseTier.TIER_4_3MODULE, 6f);
		} else {
			picker.add(PirateBaseTier.TIER_4_3MODULE, 10f);
			picker.add(PirateBaseTier.TIER_5_3MODULE, 8f);
		}
		return picker.pick();
	}

	/** Human-readable tier range a base would spawn at given the current chest. */
	public String getExpectedTierRangeForChest() {
		float ratio = PiratePatData.getChest() / Math.max(1f, PiratePatConfig.baseCost());
		if (ratio < 1f) return "1-2";
		if (ratio < 2f) return "2-3";
		if (ratio < 4f) return "3-4";
		return "4-5";
	}

	/**
	 * Adopt raiding bases that already exist in the save (vanilla-spawned, or
	 * ours after a reload) so this manager advances and counts them. Bases
	 * from PlayerRelatedPirateBaseManager (colony-crisis flavor bases) are
	 * left alone.
	 */
	public void adoptExistingBases() {
		for (IntelInfoPlugin intel : Global.getSector().getIntelManager().getIntel(PirateBaseIntel.class)) {
			PirateBaseIntel base = (PirateBaseIntel) intel;
			if (base.isEnding() || base.isEnded()) continue;
			if (base.isTargetPlayerColoniesOnly()) continue;
			if (active.contains(base)) continue;
			addActive(base);
			if (PiratePatConfig.debugLogging()) {
				log.info("Adopted existing pirate base in " + base.getSystem().getName());
			}
		}
	}

	/** The operating (raiding) bases this manager runs. */
	public List<PirateBaseIntel> getBases() {
		List<PirateBaseIntel> result = new ArrayList<PirateBaseIntel>();
		for (EveryFrameScript s : active) {
			if (s instanceof PirateBaseIntel) {
				PirateBaseIntel base = (PirateBaseIntel) s;
				if (!base.isEnding() && !base.isEnded()) result.add(base);
			}
		}
		return result;
	}

	/**
	 * Pirates preying on trade shipping is plunder, and plunder funds the war
	 * chest. Polls every colony sector-wide for shipping disruptions; a fresh
	 * disruption (its timeLeft rose since the last poll) credits the chest by
	 * the colony's size times the pirate-attributable fraction. No per-event
	 * ledger line - the pending total is summarized monthly.
	 *
	 * Cheap: the full scan only null-checks a condition; the pirate-strength
	 * computation runs only for markets actually disrupted (a couple dozen
	 * sector-wide at most), and the whole poll fires on a ~5-day game-time
	 * interval, so time compression never multiplies it.
	 */
	protected void pollShippingDisruptions() {
		if (lastDisruptionSeen == null) lastDisruptionSeen = new HashMap<String, Float>();
		Set<String> stillDisrupted = new HashSet<String>();
		float rate = PiratePatConfig.plunderPerDisruptionPerSize();

		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			MarketConditionAPI mc = market.getCondition(Conditions.SHIPPING_DISRUPTION);
			if (mc == null || !(mc.getPlugin() instanceof ShippingDisruption)) continue;
			float timeLeft = ((ShippingDisruption) mc.getPlugin()).getDisruptionTimeLeft();
			if (timeLeft <= 0f) continue;

			String id = market.getId();
			stillDisrupted.add(id);
			Float last = lastDisruptionSeen.get(id);
			lastDisruptionSeen.put(id, timeLeft);

			// suppress crediting on the first poll after a load
			if (!plunderBaselined) continue;
			// fresh if newly disrupted, or timeLeft jumped up (reset to ~120)
			boolean fresh = (last == null) || (timeLeft > last + 1f);
			if (!fresh) continue;

			float pf = pirateFractionOfDanger(market);
			if (pf <= 0f) continue;
			PiratePatData.addPlunder(rate * market.getSize() * pf);
		}

		// forget markets no longer disrupted so the map stays small
		lastDisruptionSeen.keySet().retainAll(stillDisrupted);
		plunderBaselined = true;
	}

	/**
	 * How much of the danger around a colony is the pirates' doing. A pirate
	 * activity condition (only applied by a pirate base targeting the system)
	 * is full attribution; otherwise the pirate share of local enemy strength.
	 */
	protected float pirateFractionOfDanger(MarketAPI market) {
		StarSystemAPI system = market.getStarSystem();
		if (system == null) return 0f;
		if (market.hasCondition(Conditions.PIRATE_ACTIVITY)) return 1f;

		FactionAPI pirates = Global.getSector().getFaction(Factions.PIRATES);
		FactionAPI owner = market.getFaction();
		if (pirates == null || owner == null) return 0f;
		if (!pirates.isHostileTo(owner)) return 0f;

		float pirateStr = WarSimScript.getFactionStrength(pirates, system);
		if (pirateStr <= 0f) return 0f;
		float enemyStr = WarSimScript.getEnemyStrength(owner, system);
		if (enemyStr <= pirateStr) return 1f;
		return pirateStr / enemyStr;
	}

	/**
	 * Vanilla base defenses never grow with tier: 2 light + 1 medium patrol at
	 * ~0.75x fleet size, whether the station is a wreck or fully restored.
	 * With scaling on, higher tiers field more and bigger patrols. Applied via
	 * dynamic stats on the (hidden) base market, so the ordinary patrol
	 * spawning machinery picks it up live.
	 */
	protected void applyDefenseScaling() {
		boolean enabled = PiratePatConfig.enabled() && PiratePatConfig.defenseScaling();
		for (PirateBaseIntel base : getBases()) {
			MarketAPI market = base.getMarket();
			if (market == null) continue;

			if (!enabled) {
				market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD).unmodifyFlat(DEFENSE_MOD_ID);
				market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD).unmodifyFlat(DEFENSE_MOD_ID);
				market.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT).unmodifyMult(DEFENSE_MOD_ID);
				continue;
			}

			int ord = base.getTier().ordinal(); // 0..4
			int medium = 0;
			int heavy = 0;
			if (ord == 2) medium = 1;
			else if (ord == 3) { medium = 1; heavy = 1; }
			else if (ord >= 4) { medium = 2; heavy = 2; }

			float sizeMult = 1f + (PiratePatConfig.defenseFleetSizeMax() - 1f) * ord / 4f;

			market.getStats().getDynamic().getMod(Stats.PATROL_NUM_MEDIUM_MOD)
					.modifyFlat(DEFENSE_MOD_ID, medium, "War chest funding");
			market.getStats().getDynamic().getMod(Stats.PATROL_NUM_HEAVY_MOD)
					.modifyFlat(DEFENSE_MOD_ID, heavy, "War chest funding");
			market.getStats().getDynamic().getMod(Stats.COMBAT_FLEET_SIZE_MULT)
					.modifyMult(DEFENSE_MOD_ID, sizeMult, "War chest funding");
		}
	}
}
