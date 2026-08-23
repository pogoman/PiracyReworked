package piratepat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
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
		active.removeAll(remove);

		econInterval.advance(days);
		if (econInterval.intervalElapsed()) {
			if (PiratePatConfig.enabled()) {
				float income = PiratePatConfig.incomePerBasePerMonth() * getActiveCount();
				PiratePatData.addPassiveIncome(income);
				PiratePatData.decayNetTrade();
			}
			applyDefenseScaling();
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
				float spoils = fp * PiratePatConfig.raidCostPerFP()
						* PiratePatConfig.raidReturnCostFraction();
				String targetName = "hostile worlds";
				if (raid.getSystem() != null) {
					targetName = raid.getSystem().getNameWithNoType();
					for (MarketAPI curr : Misc.getMarketsInLocation(raid.getSystem())) {
						if (curr.getFaction().isHostileTo(raid.getFaction())) {
							spoils += curr.getSize() * PiratePatConfig.raidReturnPerMarketSize();
						}
					}
				}
				PiratePatData.addRaidReturn(spoils, targetName);
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

	/** Cost of the pirates' next base: baseCost * growth^(operating bases). */
	public float getCurrentBaseCost() {
		return PiratePatConfig.baseCost()
				* (float) Math.pow(PiratePatConfig.baseCostGrowth(), getActiveCount());
	}

	/**
	 * Vanilla tier table by campaign time, WITHOUT the vanilla rule that adds
	 * 200 days per destroyed base - in this economy, killing a base destroys
	 * the pirates' investment rather than teaching them to build better ones.
	 */
	@Override
	protected PirateBaseTier pickTier() {
		float days = getDaysSinceStart();

		WeightedRandomPicker<PirateBaseTier> picker = new WeightedRandomPicker<PirateBaseTier>();
		if (days < 360) {
			picker.add(PirateBaseTier.TIER_1_1MODULE, 10f);
			picker.add(PirateBaseTier.TIER_2_1MODULE, 10f);
		} else if (days < 720f) {
			picker.add(PirateBaseTier.TIER_2_1MODULE, 10f);
			picker.add(PirateBaseTier.TIER_3_2MODULE, 10f);
		} else if (days < 1080f) {
			picker.add(PirateBaseTier.TIER_3_2MODULE, 10f);
			picker.add(PirateBaseTier.TIER_4_3MODULE, 10f);
		} else {
			picker.add(PirateBaseTier.TIER_3_2MODULE, 10f);
			picker.add(PirateBaseTier.TIER_4_3MODULE, 10f);
			picker.add(PirateBaseTier.TIER_5_3MODULE, 10f);
		}
		return picker.pick();
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
