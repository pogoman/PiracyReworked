package piratepat;

import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel;
import com.fs.starfarer.api.impl.campaign.intel.raid.RaidIntel.RaidStageStatus;
import com.fs.starfarer.api.util.Misc;

/**
 * A war-chest-funded pirate base. Behaves exactly like a vanilla base except
 * that its raids run on the ledger: launching costs the chest credits per
 * fleet point (a broke underworld doesn't sail), and a successful raid pays
 * spoils back into it. Repelled raids are a pure loss.
 */
public class PatronageBaseIntel extends PirateBaseIntel {

	protected StarSystemAPI lastRaidTarget = null;

	public PatronageBaseIntel(StarSystemAPI system, String factionId, PirateBaseTier tier) {
		super(system, factionId, tier);
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
		PiratePatData.incrRaidsLaunched();
		super.startRaid(target, raidFP);
	}

	@Override
	public void notifyRaidEnded(RaidIntel raid, RaidStageStatus status) {
		super.notifyRaidEnded(raid, status);
		if (!PiratePatConfig.enabled()) return;

		if (status == RaidStageStatus.SUCCESS) {
			float fp = getBaseRaidFP();
			if (raid != null && raid.getAssembleStage() != null) {
				fp = raid.getAssembleStage().getOrigSpawnFP();
			}
			float spoils = fp * PiratePatConfig.raidCostPerFP()
					* PiratePatConfig.raidReturnCostFraction();

			StarSystemAPI target = lastRaidTarget;
			String targetName = "hostile worlds";
			if (target != null) {
				targetName = target.getNameWithNoType();
				for (MarketAPI curr : Misc.getMarketsInLocation(target)) {
					if (curr.getFaction().isHostileTo(getFactionForUIColors())) {
						spoils += curr.getSize() * PiratePatConfig.raidReturnPerMarketSize();
					}
				}
			}
			PiratePatData.addRaidReturn(spoils, targetName);
		} else {
			PiratePatData.incrRaidsDefeated();
		}
	}
}
