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
 * Export volumes are counted once at the source (every unit exported is a
 * unit imported somewhere), valued at commodity base price. Free ports fence
 * at a multiplier - they are where everyone else's contraband becomes legal
 * cargo. Player-owned markets are tracked separately so the intel screen can
 * show the player exactly how much of the underworld's baseline their own
 * colonies provide; it is chest income only and never counts as personal
 * patronage.
 *
 * With zero operating bases only a fraction flows - with no organization to
 * collect it, most of the take stays in local hands. Eradication buys years
 * of quiet, but piracy follows interstellar civilization: the trickle
 * eventually re-founds a base.
 */
public class UnderworldTithe {

	public static Logger log = Global.getLogger(UnderworldTithe.class);

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

			float illegalValue = 0f;
			for (CommodityOnMarketAPI com : market.getAllCommodities()) {
				if (com.isNonEcon()) continue;
				int exported = Math.min(com.getAvailable(), com.getMaxSupply());
				if (exported <= 0) continue;
				if (com.getCommodityMarketData() == null) continue;
				MarketShareDataAPI share = com.getCommodityMarketData().getMarketShareData(market);
				if (share == null || !share.isSourceIsIllegal()) continue;
				illegalValue += exported * com.getCommodity().getBasePrice();
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
