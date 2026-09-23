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

	private static String s(String key) {
		if (lunaAvailable()) {
			String v = LunaConfigBridge.getString(key);
			if (v != null && !v.trim().isEmpty()) return v;
		}
		return Global.getSettings().getString(key);
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

	public static boolean titheEnabled() { return b("piratepat_titheEnabled"); }
	public static float titheRate() { return f("piratepat_titheRate"); }
	public static float titheFreePortMult() { return f("piratepat_titheFreePortMult"); }
	public static float titheNoBaseFraction() { return f("piratepat_titheNoBaseFraction"); }

	public static boolean brokerEnabled() { return b("piratepat_brokerEnabled"); }

	/**
	 * Minimum rep with the CONTACT before the sourcing service is offered -
	 * same personal-rep gate vanilla's person_missions.csv uses (min rep
	 * column). Falls back to FAVORABLE on an unparseable value.
	 */
	public static com.fs.starfarer.api.campaign.RepLevel brokerMinRep() {
		try {
			return com.fs.starfarer.api.campaign.RepLevel.valueOf(
					s("piratepat_brokerMinRep").trim().toUpperCase());
		} catch (Throwable t) {
			return com.fs.starfarer.api.campaign.RepLevel.FAVORABLE;
		}
	}

	public static float brokerImportanceCapBase() { return i("piratepat_brokerImportanceCapBase"); }
	public static float brokerDefensePremiumPer100() { return f("piratepat_brokerDefensePremiumPer100"); }
	public static float brokerPriceMult() { return f("piratepat_brokerPriceMult"); }
	public static int brokerMaxConcurrent() { return i("piratepat_brokerMaxConcurrent"); }
	public static int brokerBpOffers() { return i("piratepat_brokerBpOffers"); }
	public static float brokerDebtFraction() { return f("piratepat_brokerDebtFraction"); }
	public static float brokerRefundFailed() { return f("piratepat_brokerRefundFailed"); }
	public static float brokerRefundUnserved() { return f("piratepat_brokerRefundUnserved"); }
	public static float brokerStallDays() { return i("piratepat_brokerStallDays"); }

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

	public static boolean baseBountyScaling() { return b("piratepat_baseBountyScaling"); }
	public static float baseBountyTier1() { return i("piratepat_baseBountyTier1"); }
	public static float baseBountyPerTier() { return i("piratepat_baseBountyPerTier"); }

	// --- Debt ---

	public static boolean debtEnabled() { return b("piratepat_debtEnabled"); }
	public static float debtTariffFraction() { return f("piratepat_debtTariffFraction"); }
	public static float debtSuspicionFloor() { return f("piratepat_debtSuspicionFloor"); }
	public static float debtSuspicionFull() { return f("piratepat_debtSuspicionFull"); }
	public static float debtInterestPerMonth() { return f("piratepat_debtInterestPerMonth"); }
	public static float debtPirateInterestPerMonth() { return f("piratepat_debtPirateInterestPerMonth"); }
	public static float debtCollectorMinDebt() { return i("piratepat_debtCollectorMinDebt"); }
	public static int debtNoticeDays() { return i("piratepat_debtNoticeDays"); }

	/**
	 * Days of grace before any collector can spawn.
	 *
	 * <p>Clamped to at least debtNoticeDays (critique F11): a LunaLib user who
	 * sets grace to 0 must not be ambushed before the written notice has even
	 * arrived. The clamp lives here rather than at the call sites so every
	 * caller gets it for free.
	 */
	public static int debtGraceDays() {
		return Math.max(i("piratepat_debtGraceDays"), debtNoticeDays());
	}

	public static float debtSpawnProb() { return f("piratepat_debtSpawnProb"); }

	// THE collector - there is only ever one, sent at full strength. Sized off
	// the player's combat FP alone; the floor is clamped to the player in
	// PirateDebt.collectorFP and the cap is the ceiling for that one fleet.
	public static float debtCollectorFPRatio() { return f("piratepat_debtCollectorFPRatio"); }
	public static int debtCollectorMinFP() { return i("piratepat_debtCollectorMinFP"); }
	public static int debtCollectorMaxFP() { return i("piratepat_debtCollectorMaxFP"); }
	public static int debtCollectorHuntDays() { return i("piratepat_debtCollectorHuntDays"); }
	public static int debtCollectorBurnBoost() { return i("piratepat_debtCollectorBurnBoost"); }

	// the officer rule's one fork: with this on, destroying the house's crew
	// sells the paper to the pirates instead of ending the debt, and it is
	// their crew whose officers have to die. Off = the debt dies with whichever
	// crew loses its last officer.
	public static boolean debtSellOnHouseDefeat() { return b("piratepat_debtSellOnHouseDefeat"); }

	public static float debtPirateMarkupMin() { return f("piratepat_debtPirateMarkupMin"); }
	public static float debtPirateMarkupMax() { return f("piratepat_debtPirateMarkupMax"); }
	public static float debtDuckSurcharge() { return f("piratepat_debtDuckSurcharge"); }
	public static int debtChallengeStoryPoints() { return i("piratepat_debtChallengeStoryPoints"); }
	public static float debtInstallmentFraction() { return f("piratepat_debtInstallmentFraction"); }
	public static float debtInstallmentMin() { return i("piratepat_debtInstallmentMin"); }
	public static float debtLateFeeFraction() { return f("piratepat_debtLateFeeFraction"); }
	public static int debtMissesBeforeEnforcement() { return i("piratepat_debtMissesBeforeEnforcement"); }
	public static int debtMissesBeforeSale() { return i("piratepat_debtMissesBeforeSale"); }
	public static int debtEnforcementDays() { return i("piratepat_debtEnforcementDays"); }
	public static int pirateRepPerCollectorKill() { return i("piratepat_pirateRepPerCollectorKill"); }
	public static int pirateRepPerPirateCollectorKill() { return i("piratepat_pirateRepPerPirateCollectorKill"); }

	// forced collection when the player fights a collector and loses
	public static boolean debtSeizeEnabled() { return b("piratepat_debtSeizeEnabled"); }
	public static float debtSeizeCargoFenceRate() { return f("piratepat_debtSeizeCargoFenceRate"); }
	public static float debtSeizeSupplyFloor() { return i("piratepat_debtSeizeSupplyFloor"); }
	public static float debtSeizeFuelFloor() { return i("piratepat_debtSeizeFuelFloor"); }
	public static boolean debtSeizeSpecialItems() { return b("piratepat_debtSeizeSpecialItems"); }

	public static boolean respitePiercing() { return b("piratepat_respitePiercing"); }
	public static float pierceMinContribution() { return i("piratepat_pierceMinContribution"); }
	public static float pierceMinShare() { return f("piratepat_pierceMinShare"); }

	public static boolean intelShowExact() { return b("piratepat_intelShowExact"); }
	public static boolean debugLogging() { return b("piratepat_debugLogging"); }
}
