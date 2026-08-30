package piratepat;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.CommodityOnMarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketShareDataAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The underworld's tithe on the sector's illegal trade. Vanilla's economy
 * already knows which commodity flows are contraband - the same
 * isSourceIsIllegal flag EconomyFleetRouteManager uses to decide whether a
 * trade fleet sails as a smuggler. Every month the war chest collects a cut
 * of that flow, sector-wide: pirate income that scales with the size of the
 * economy instead of with the player's personal black market habit.
 *
 * Both ends of every illegal flow pay, deliberately: exports where the
 * market's own supply of a commodity is illegal-source, and imports in
 * proportion to how much of that commodity's global export share belongs to
 * illegal sources - the smuggler pays protection at the origin and the fence
 * takes a cut at the destination. This is what makes a big consumer colony
 * matter: its population's drug and organ demand is met by smuggling INTO
 * it, and that throughput is underworld revenue whether or not the colony
 * produces anything illegal itself. Values are commodity base price. Free
 * ports fence at a multiplier - they are where everyone else's contraband
 * becomes legal cargo. Player-owned markets are tracked separately so the
 * intel screen can show the player exactly how much of the underworld's
 * baseline their own colonies provide; it is chest income only and never
 * counts as personal patronage.
 *
 * With zero operating bases only a fraction flows - with no organization to
 * collect it, most of the take stays in local hands. Eradication buys years
 * of quiet, but piracy follows interstellar civilization: the trickle
 * eventually re-founds a base.
 */
public class UnderworldTithe {

	public static Logger log = Global.getLogger(UnderworldTithe.class);

	/**
	 * Factions outside the underworld economy: machine and outsider factions
	 * (the Threat hive, Remnants, Omega, dwellers, derelicts) neither pay
	 * protection money nor hold anything a fence can move - and raiding them
	 * is suicide, not business. Excluded from the tithe and from raid
	 * targeting alike.
	 */
	public static boolean isOutsideUnderworldEconomy(FactionAPI faction) {
		if (faction == null) return true;
		String id = faction.getId();
		return com.fs.starfarer.api.impl.campaign.ids.Factions.THREAT.equals(id)
				|| com.fs.starfarer.api.impl.campaign.ids.Factions.REMNANTS.equals(id)
				|| com.fs.starfarer.api.impl.campaign.ids.Factions.OMEGA.equals(id)
				|| com.fs.starfarer.api.impl.campaign.ids.Factions.DWELLER.equals(id)
				|| com.fs.starfarer.api.impl.campaign.ids.Factions.DERELICT.equals(id);
	}

	public static class Result {
		public float total;
		public float playerColonies;
	}

	/** Compute one month's tithe without applying it. */
	public static Result compute() {
		Result result = new Result();

		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden()) continue;
			FactionAPI faction = market.getFaction();
			if (faction == null || Misc.isPirateFaction(faction)) continue;
			if (isOutsideUnderworldEconomy(faction)) continue;

			float illegalValue = 0f;
			for (CommodityOnMarketAPI com : market.getAllCommodities()) {
				if (com.isNonEcon()) continue;
				if (com.getCommodityMarketData() == null) continue;
				float basePrice = com.getCommodity().getBasePrice();

				// export side: this market's own supply is illegal-source
				int exported = Math.min(com.getAvailable(), com.getMaxSupply());
				if (exported > 0) {
					MarketShareDataAPI share = com.getCommodityMarketData().getMarketShareData(market);
					if (share != null && share.isSourceIsIllegal()) {
						illegalValue += exported * basePrice;
					}
				}

				// import side: demand met here, attributed to illegal sources
				// by their share of the commodity's global exports - the
				// smuggled-into-this-market portion of consumption
				int met = Math.min(com.getAvailable(), com.getMaxDemand());
				if (met > 0) {
					float illegalShare = 0f;
					float totalShare = 0f;
					for (MarketShareDataAPI producer : com.getCommodityMarketData().getSortedProducers()) {
						float s = producer.getExportMarketShare();
						if (s <= 0) continue;
						totalShare += s;
						if (producer.isSourceIsIllegal()) illegalShare += s;
					}
					if (totalShare > 0 && illegalShare > 0) {
						illegalValue += met * basePrice * (illegalShare / totalShare);
					}
				}
			}
			if (illegalValue <= 0) continue;

			float tithe = illegalValue * PiratePatConfig.titheRate();
			if (market.isFreePort()) tithe *= PiratePatConfig.titheFreePortMult();

			result.total += tithe;
			if (market.isPlayerOwned()) result.playerColonies += tithe;

			if (PiratePatConfig.debugLogging()) {
				log.info("Tithe from " + market.getName() + " (" + faction.getId() + "): "
						+ (int) tithe + " (illegal trade value " + (int) illegalValue
						+ (market.isFreePort() ? ", free port" : "") + ")");
			}
		}
		return result;
	}

	/** Collect one month's tithe into the war chest. */
	public static void collectMonthly(int operatingBases) {
		if (!PiratePatConfig.titheEnabled()) return;

		Result result = compute();
		if (operatingBases <= 0) {
			float fraction = PiratePatConfig.titheNoBaseFraction();
			result.total *= fraction;
			result.playerColonies *= fraction;
		}
		if (result.total < 1f) return;

		PiratePatData.addTithe(result.total, result.playerColonies);

		if (PiratePatConfig.debugLogging()) {
			log.info("Monthly underworld tithe: " + (int) result.total
					+ " (player colonies: " + (int) result.playerColonies
					+ ", operating bases: " + operatingBases + ")");
		}
	}
}
