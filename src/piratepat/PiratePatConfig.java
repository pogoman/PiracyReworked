package piratepat;

import com.fs.starfarer.api.Global;

/**
 * Central settings accessor: LunaLib in-game menu when available, bundled
 * data/config/settings.json as the standalone fallback.
 */
public class PiratePatConfig {

	public static final String MOD_ID = "piratepat";

	private static Boolean lunaEnabled = null;

	public static boolean lunaAvailable() {
		if (lunaEnabled == null) {
			lunaEnabled = Global.getSettings().getModManager().isModEnabled("lunalib");
		}
		return lunaEnabled;
	}

	private static int i(String key) {
		if (lunaAvailable()) {
			Integer v = LunaConfigBridge.getInt(key);
			if (v != null) return v;
		}
		return (int) Global.getSettings().getFloat(key);
	}

	private static float f(String key) {
		if (lunaAvailable()) {
			Float v = LunaConfigBridge.getFloat(key);
			if (v != null) return v;
		}
		return Global.getSettings().getFloat(key);
	}

	private static boolean b(String key) {
		if (lunaAvailable()) {
			Boolean v = LunaConfigBridge.getBoolean(key);
			if (v != null) return v;
		}
		return Global.getSettings().getBoolean(key);
	}

	public static boolean enabled() { return b("piratepat_enabled"); }

	public static float baseCost() { return i("piratepat_baseCost"); }
	public static float tierCostGrowth() { return f("piratepat_tierCostGrowth"); }
	public static int recoveryBases() { return i("piratepat_recoveryBases"); }
	public static float rebuildFreezeMinMonths() { return f("piratepat_rebuildFreezeMinMonths"); }
	public static float rebuildFreezeMaxMonths() { return f("piratepat_rebuildFreezeMaxMonths"); }

	/** Cost of a base by tier ordinal (0-based; baseCost anchors tier 2). */
	public static float tierCost(int tierOrdinal) {
		return baseCost() * (float) Math.pow(tierCostGrowth(), tierOrdinal - 1);
	}
	public static float incomePerBasePerMonth() { return i("piratepat_incomePerBasePerMonth"); }
	public static float raidCostPerFP() { return i("piratepat_raidCostPerFP"); }
	public static float raidReturnCostFraction() { return f("piratepat_raidReturnCostFraction"); }
	public static float raidReturnPerMarketSize() { return i("piratepat_raidReturnPerMarketSize"); }
	public static int seedBases() { return i("piratepat_seedBases"); }
	public static float seedReserve() { return i("piratepat_seedReserve"); }
	public static int maxBases() { return i("piratepat_maxBases"); }
	public static float baseKillContributionOffset() { return i("piratepat_baseKillContributionOffset"); }
	public static float offsetPerPirateFPDestroyed() { return i("piratepat_offsetPerPirateFPDestroyed"); }

	public static boolean plunderEnabled() { return b("piratepat_plunderEnabled"); }
	public static float plunderPerDisruptionPerSize() { return i("piratepat_plunderPerDisruptionPerSize"); }

	public static float buyWeight() { return f("piratepat_buyWeight"); }
	public static float weaponWeight() { return f("piratepat_weaponWeight"); }
	public static float shipWeight() { return f("piratepat_shipWeight"); }
	public static float commodityWeight() { return f("piratepat_commodityWeight"); }
	public static float oreWeight() { return f("piratepat_oreWeight"); }
	public static float blueprintBonus() { return i("piratepat_blueprintBonus"); }

	public static boolean garrisonEnabled() { return b("piratepat_garrisonEnabled"); }
	public static float garrisonFPBase() { return i("piratepat_garrisonFPBase"); }
	public static float garrisonFPPerTier() { return i("piratepat_garrisonFPPerTier"); }
	public static float garrisonRespawnDays() { return i("piratepat_garrisonRespawnDays"); }

	public static boolean defenseScaling() { return b("piratepat_defenseScaling"); }
	public static float defenseFleetSizeMax() { return f("piratepat_defenseFleetSizeMax"); }

	public static boolean bountyEnabled() { return b("piratepat_bountyEnabled"); }
	public static float bountySuspicionFloor() { return f("piratepat_bountySuspicionFloor"); }
	public static float bountySuspicionFull() { return f("piratepat_bountySuspicionFull"); }
	public static float bountyActivationMin() { return i("piratepat_bountyActivationMin"); }
	public static float bountyCreditsPerFP() { return i("piratepat_bountyCreditsPerFP"); }
	public static float bountyMaxFPPerFleet() { return i("piratepat_bountyMaxFPPerFleet"); }
	public static float bountyCreditsPerExtraFleet() { return i("piratepat_bountyCreditsPerExtraFleet"); }
	public static int bountyMaxFleetsPerFaction() { return i("piratepat_bountyMaxFleetsPerFaction"); }
	public static float bountySpawnProb() { return f("piratepat_bountySpawnProb"); }
	public static float bountyDecayPerMonth() { return f("piratepat_bountyDecayPerMonth"); }
	public static float bountyWorthItFraction() { return f("piratepat_bountyWorthItFraction"); }
	public static float bountyPerKillFlat() { return i("piratepat_bountyPerKillFlat"); }
	public static float bountyPerKillFraction() { return f("piratepat_bountyPerKillFraction"); }
	public static float bountyPayoffMult() { return f("piratepat_bountyPayoffMult"); }
	public static int pirateRepPerHunterKill() { return i("piratepat_pirateRepPerHunterKill"); }

	public static boolean respitePiercing() { return b("piratepat_respitePiercing"); }
	public static float pierceMinContribution() { return i("piratepat_pierceMinContribution"); }
	public static float pierceMinShare() { return f("piratepat_pierceMinShare"); }

	public static boolean intelShowExact() { return b("piratepat_intelShowExact"); }
	public static boolean debugLogging() { return b("piratepat_debugLogging"); }
}
