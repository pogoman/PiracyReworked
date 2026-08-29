package piratepat;

import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel.RaidStageStatus;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission;
import com.fs.starfarer.api.util.Misc;

/**
 * A war-chest-funded pirate base. Behaves exactly like a vanilla base except
 * that its raids run on the ledger: launching costs the chest credits per
 * fleet point (a broke underworld doesn't sail), and a successful raid pays
 * spoils back into it. Repelled raids are a pure loss.
 */
public class PatronageBaseIntel extends PirateBaseIntel {

	public static final String GARRISON_FLAG = "$piratepat_garrison";

	protected StarSystemAPI lastRaidTarget = null;

	// broker commission riding on this base's next raid, if any; also a
	// raids-in-flight counter so a commission never launches while another
	// raid from this base is still out (its outcome report would be ambiguous)
	protected CommissionIntel commission = null;
	protected int raidsInFlight = 0;

	// transient: rebuilt each load; the garrison fleet itself persists in the
	// world and is re-found by flag, so these just gate spawn/respawn timing
	protected transient boolean garrisonSpawnedOnce;
	protected transient float garrisonRespawnElapsed;

	public PatronageBaseIntel(StarSystemAPI system, String factionId, PirateBaseTier tier) {
		super(system, factionId, tier);
	}

	@Override
	protected void advanceImpl(float amount) {
		super.advanceImpl(amount);
		if (!PiratePatConfig.enabled() || !PiratePatConfig.garrisonEnabled()) return;
		manageGarrison(Global.getSector().getClock().convertToDays(amount));
	}

	/**
	 * Keeps a tier-scaled defensive fleet stationed on the base. A brand-new
	 * base is defended at once (no sniping newborns); a destroyed garrison
	 * re-forms after a delay while the base stands. The fleet is found in the
	 * world by flag, so it survives save/load without duplicating.
	 */
	protected void manageGarrison(float days) {
		if (isEnding() || isEnded()) return;
		SectorEntityToken e = getEntity();
		if (e == null || e.getContainingLocation() == null) return;

		CampaignFleetAPI g = findGarrison();
		if (g != null) {
			garrisonSpawnedOnce = true;
			garrisonRespawnElapsed = 0f;
			return;
		}
		if (!garrisonSpawnedOnce) { // fresh base: defend immediately
			garrisonSpawnedOnce = true;
			spawnGarrison();
			return;
		}
		garrisonRespawnElapsed += days;
		if (garrisonRespawnElapsed < PiratePatConfig.garrisonRespawnDays()) return;
		garrisonRespawnElapsed = 0f;
		spawnGarrison();
	}

	protected CampaignFleetAPI findGarrison() {
		SectorEntityToken e = getEntity();
		if (e == null || e.getContainingLocation() == null) return null;
		String id = getMarket().getId();
		for (CampaignFleetAPI f : e.getContainingLocation().getFleets()) {
			if (f.isAlive() && id.equals(f.getMemoryWithoutUpdate().getString(GARRISON_FLAG))) {
				return f;
			}
		}
		return null;
	}

	protected void spawnGarrison() {
		SectorEntityToken e = getEntity();
		if (e == null || e.getContainingLocation() == null) return;

		int ord = getTier().ordinal();
		float fp = PiratePatConfig.garrisonFPBase() + ord * PiratePatConfig.garrisonFPPerTier();
		if (fp <= 0f) return;
		int difficulty = Math.round(fp / 18f);
		if (difficulty < 1) difficulty = 1;
		if (difficulty > 20) difficulty = 20;

		FleetCreatorMission m = new FleetCreatorMission(new Random());
		m.beginFleet();
		m.createStandardFleet(difficulty, getMarket().getFactionId(), e.getLocationInHyperspace());
		m.triggerSetPirateFleet();
		m.triggerMakeLowRepImpact();
		m.triggerMakeHostileAndAggressive();

		CampaignFleetAPI fleet = m.createFleet();
		if (fleet == null) return;

		fleet.setName("Base Garrison");
		fleet.getMemoryWithoutUpdate().set(GARRISON_FLAG, getMarket().getId());
		e.getContainingLocation().addEntity(fleet);
		fleet.setLocation(e.getLocation().x, e.getLocation().y);
		fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, e, Float.MAX_VALUE, "guarding the base");
	}

	protected void despawnGarrison() {
		CampaignFleetAPI g = findGarrison();
		if (g != null && g.getContainingLocation() != null) {
			g.getContainingLocation().removeEntity(g);
		}
	}

	@Override
	protected void notifyEnding() {
		super.notifyEnding();
		despawnGarrison();
	}

	/**
	 * Same as vanilla, but the pirate-activity intel this base issues for its
	 * target system is the respite-piercing variant.
	 */
	@Override
	public void updateTarget() {
		StarSystemAPI newTarget = pickTarget();
		if (newTarget == target) return;

		clearTarget();

		target = newTarget;
		monthsWithSameTarget = 0;

		if (target != null) {
			new PatronageActivityIntel(target, this);
		}
	}

	@Override
	public void startRaid(StarSystemAPI target, float raidFP) {
		if (!PiratePatConfig.enabled()) {
			super.startRaid(target, raidFP);
			return;
		}
		if (target == null) return;

		// replicate vanilla's pre-launch checks so the chest is never charged
		// for a raid that couldn't have launched anyway
		if (!Misc.getMarketsInLocation(target, Factions.PLAYER).isEmpty()) return;
		boolean hasTargets = false;
		for (MarketAPI curr : Misc.getMarketsInLocation(target)) {
			if (curr.getFaction().isHostileTo(getFactionForUIColors())) {
				hasTargets = true;
				break;
			}
		}
		if (!hasTargets) return;

		float cost = raidFP * PiratePatConfig.raidCostPerFP();
		if (!PiratePatData.trySpend(cost, "A raid fleet is outfitted against "
				+ target.getNameWithNoType())) {
			PiratePatData.addLedger("A planned raid is scrapped - the war chest is empty", 0f);
			return;
		}

		lastRaidTarget = target;
		// detect whether the raid actually launched (super can still bail on
		// missing jump points) so in-flight tracking and stats stay honest
		int before = Global.getSector().getIntelManager().getIntel(RaidIntel.class).size();
		super.startRaid(target, raidFP);
		int after = Global.getSector().getIntelManager().getIntel(RaidIntel.class).size();
		if (after > before) {
			raidsInFlight++;
			PiratePatData.incrRaidsLaunched();
		}
	}

	/** No commission while another raid is out - its report would be ambiguous. */
	public boolean canCarryCommission() {
		return commission == null && raidsInFlight == 0;
	}

	/**
	 * Launch a raid on behalf of a broker commission. The raid is a normal
	 * war-chest raid in every respect (launch cost, spoils, repel-ability);
	 * the commission just rides along and hears about the outcome.
	 */
	public boolean launchCommissionRaid(StarSystemAPI target, CommissionIntel c) {
		if (!canCarryCommission()) return false;
		commission = c;
		int before = raidsInFlight;
		startRaid(target, getRaidFP());
		if (raidsInFlight <= before) {
			commission = null;
			return false;
		}
		// hold the base's own raid roll until the commission raid resolves
		raidTimeoutMonths = Math.max(raidTimeoutMonths, 2);
		return true;
	}

	@Override
	public void notifyRaidEnded(RaidIntel raid, RaidStageStatus status) {
		super.notifyRaidEnded(raid, status);

		if (raidsInFlight > 0) raidsInFlight--;
		if (commission != null) {
			CommissionIntel c = commission;
			commission = null;
			c.reportRaidOutcome(status == RaidStageStatus.SUCCESS);
		}

		if (!PiratePatConfig.enabled()) return;

		if (status == RaidStageStatus.SUCCESS) {
			float fp = getBaseRaidFP();
			if (raid != null && raid.getAssembleStage() != null) {
				fp = raid.getAssembleStage().getOrigSpawnFP();
			}
			PatronageBaseManager.settleSuccessfulRaid(lastRaidTarget, getFactionForUIColors(), fp);
		} else {
			PiratePatData.incrRaidsDefeated();
		}
	}
}
