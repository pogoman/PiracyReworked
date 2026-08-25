package piratepat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.Misc;

/**
 * Persistent war chest state: the chest itself, lifetime bookkeeping for the
 * intel balance sheet, a human-readable ledger of recent events, and the
 * rolling net-trade tracker that makes wash-trading contribute nothing.
 * Everything lives in sector persistent data.
 */
public class PiratePatData {

	public static Logger log = Global.getLogger(PiratePatData.class);

	public static final String KEY_CHEST = "piratepat_chest";
	public static final String KEY_SEEDED = "piratepat_seeded";
	public static final String KEY_LEDGER = "piratepat_ledger";
	public static final String KEY_NET_BOUGHT = "piratepat_netBought";
	public static final String KEY_NET_SOLD = "piratepat_netSold";

	public static final String KEY_LT_PLAYER = "piratepat_ltPlayer";
	public static final String KEY_LT_PASSIVE = "piratepat_ltPassive";
	public static final String KEY_LT_PLUNDER = "piratepat_ltPlunder";
	public static final String KEY_PLUNDER_PENDING = "piratepat_plunderPending";
	public static final String KEY_LT_RETURNS = "piratepat_ltReturns";
	public static final String KEY_LT_COSTS = "piratepat_ltCosts";
	public static final String KEY_BASES_PURCHASED = "piratepat_basesPurchased";
	public static final String KEY_RAIDS_LAUNCHED = "piratepat_raidsLaunched";
	public static final String KEY_RAIDS_SUCCEEDED = "piratepat_raidsSucceeded";
	public static final String KEY_RAIDS_DEFEATED = "piratepat_raidsDefeated";
	public static final String KEY_LT_KILL_OFFSET = "piratepat_ltKillOffset";
	public static final String KEY_BASES_DESTROYED = "piratepat_basesDestroyed";

	public static final int LEDGER_MAX_ENTRIES = 30;

	private static float getF(String key) {
		Object val = Global.getSector().getPersistentData().get(key);
		if (val instanceof Float) return (Float) val;
		return 0f;
	}

	private static void putF(String key, float value) {
		Global.getSector().getPersistentData().put(key, value);
	}

	private static int getI(String key) {
		Object val = Global.getSector().getPersistentData().get(key);
		if (val instanceof Integer) return (Integer) val;
		return 0;
	}

	private static void incrI(String key) {
		Global.getSector().getPersistentData().put(key, getI(key) + 1);
	}

	// --- war chest ---

	public static float getChest() {
		return getF(KEY_CHEST);
	}

	private static void setChest(float value) {
		if (value < 0) value = 0;
		putF(KEY_CHEST, value);
	}

	public static boolean isSeeded() {
		Object val = Global.getSector().getPersistentData().get(KEY_SEEDED);
		return val instanceof Boolean && (Boolean) val;
	}

	/**
	 * Seed the chest on first activation: an operating reserve plus enough to
	 * fund the configured number of starting bases, minus any that already
	 * exist in the save (mid-save enable adopts vanilla bases instead of
	 * duplicating them).
	 */
	public static void seedIfNeeded(int existingBases) {
		if (isSeeded()) return;
		float seed = PiratePatConfig.seedReserve();
		int toFund = Math.max(0, PiratePatConfig.seedBases() - existingBases);
		for (int i = 0; i < toFund; i++) {
			seed += PiratePatConfig.baseCost()
					* (float) Math.pow(PiratePatConfig.baseCostGrowth(), existingBases + i);
		}
		setChest(getChest() + seed);
		Global.getSector().getPersistentData().put(KEY_SEEDED, true);
		addLedger("The sector underworld pools its resources", seed);
		if (PiratePatConfig.debugLogging()) {
			log.info("Seeded war chest with " + (int) seed + " (existing bases: " + existingBases + ")");
		}
	}

	public static void addPlayerContribution(float amount, String marketName) {
		if (amount <= 0) return;
		setChest(getChest() + amount);
		putF(KEY_LT_PLAYER, getF(KEY_LT_PLAYER) + amount);
		addLedger("Fenced goods at " + marketName, amount);
		if (PiratePatConfig.debugLogging()) {
			log.info("Player black market contribution at " + marketName + ": " + (int) amount);
		}
	}

	public static void addPassiveIncome(float amount) {
		if (amount <= 0) return;
		setChest(getChest() + amount);
		putF(KEY_LT_PASSIVE, getF(KEY_LT_PASSIVE) + amount);
	}

	/**
	 * Plunder from pirate-disrupted shipping. Chest income only - never
	 * touches the player's contribution figure. Accumulates a pending total
	 * that {@link #flushPlunderLedger} periodically summarizes, to avoid
	 * per-event ledger spam across a whole sector of colonies.
	 */
	public static void addPlunder(float amount) {
		if (amount <= 0) return;
		setChest(getChest() + amount);
		putF(KEY_LT_PLUNDER, getF(KEY_LT_PLUNDER) + amount);
		putF(KEY_PLUNDER_PENDING, getF(KEY_PLUNDER_PENDING) + amount);
	}

	/** Emit one ledger line for plunder accumulated since the last flush. */
	public static void flushPlunderLedger() {
		float pending = getF(KEY_PLUNDER_PENDING);
		if (pending <= 0) return;
		putF(KEY_PLUNDER_PENDING, 0f);
		addLedger("Pirates plundered disrupted shipping across the sector", pending);
	}

	public static float getLifetimePlunder() { return getF(KEY_LT_PLUNDER); }

	public static void addRaidReturn(float amount, String targetName) {
		if (amount <= 0) return;
		setChest(getChest() + amount);
		putF(KEY_LT_RETURNS, getF(KEY_LT_RETURNS) + amount);
		incrI(KEY_RAIDS_SUCCEEDED);
		addLedger("Spoils from raiding " + targetName, amount);
	}

	/**
	 * Debits as much of the amount as the chest can cover; returns what was
	 * actually spent. Used for raids the chest cannot prevent (adopted
	 * vanilla bases launch on vanilla rules regardless of funds).
	 */
	public static float spendUpTo(float amount, String ledgerText) {
		if (amount <= 0) return 0f;
		float spent = Math.min(getChest(), amount);
		if (spent <= 0) return 0f;
		setChest(getChest() - spent);
		putF(KEY_LT_COSTS, getF(KEY_LT_COSTS) + spent);
		if (ledgerText != null) addLedger(ledgerText, -spent);
		return spent;
	}

	/** Debits the chest if it can cover the amount; returns whether it did. */
	public static boolean trySpend(float amount, String ledgerText) {
		if (amount <= 0) return true;
		if (getChest() < amount) return false;
		setChest(getChest() - amount);
		putF(KEY_LT_COSTS, getF(KEY_LT_COSTS) + amount);
		if (ledgerText != null) addLedger(ledgerText, -amount);
		return true;
	}

	/**
	 * A pirate base was destroyed: the infrastructure the player's money
	 * built is ash. Offsets the lifetime contribution figure (floor zero),
	 * which also pulls the player's income share down - redemption through
	 * demolition.
	 */
	public static void reportBaseDestroyed(String systemName) {
		incrI(KEY_BASES_DESTROYED);
		float offset = PiratePatConfig.baseKillContributionOffset();
		float contributed = getF(KEY_LT_PLAYER);
		float applied = Math.min(offset, contributed);
		if (applied > 0) {
			putF(KEY_LT_PLAYER, contributed - applied);
			putF(KEY_LT_KILL_OFFSET, getF(KEY_LT_KILL_OFFSET) + applied);
			addLedger("Pirate base in the " + systemName
					+ " destroyed - your ledger with the sector lightens", -applied);
		} else {
			addLedger("Pirate base in the " + systemName + " destroyed", 0f);
		}
	}

	/**
	 * Destroying pirate ships in battle chips away at the contribution
	 * ledger - one ledger entry per battle.
	 */
	public static void offsetFromPirateKills(float amount, int fpDestroyed) {
		if (amount <= 0) return;
		float contributed = getF(KEY_LT_PLAYER);
		float applied = Math.min(amount, contributed);
		if (applied <= 0) return;
		putF(KEY_LT_PLAYER, contributed - applied);
		putF(KEY_LT_KILL_OFFSET, getF(KEY_LT_KILL_OFFSET) + applied);
		addLedger("Destroyed pirate ships in battle (" + fpDestroyed
				+ " fleet points) - your ledger lightens", -applied);
	}

	public static float getLifetimeKillOffset() { return getF(KEY_LT_KILL_OFFSET); }
	public static int getBasesDestroyed() { return getI(KEY_BASES_DESTROYED); }

	public static void incrBasesPurchased() { incrI(KEY_BASES_PURCHASED); }
	public static void incrRaidsLaunched() { incrI(KEY_RAIDS_LAUNCHED); }
	public static void incrRaidsDefeated() {
		incrI(KEY_RAIDS_DEFEATED);
		addLedger("A raid was repelled - the investment is lost", 0f);
	}

	public static float getLifetimePlayerContribution() { return getF(KEY_LT_PLAYER); }
	public static float getLifetimePassiveIncome() { return getF(KEY_LT_PASSIVE); }
	public static float getLifetimeRaidReturns() { return getF(KEY_LT_RETURNS); }
	public static float getLifetimeCosts() { return getF(KEY_LT_COSTS); }
	public static int getBasesPurchased() { return getI(KEY_BASES_PURCHASED); }
	public static int getRaidsLaunched() { return getI(KEY_RAIDS_LAUNCHED); }
	public static int getRaidsSucceeded() { return getI(KEY_RAIDS_SUCCEEDED); }
	public static int getRaidsDefeated() { return getI(KEY_RAIDS_DEFEATED); }

	/** Player's share of all war chest income, 0..1. */
	public static float getPlayerShare() {
		float total = getLifetimePlayerContribution() + getLifetimePassiveIncome()
				+ getLifetimeRaidReturns();
		if (total <= 0) return 0f;
		return getLifetimePlayerContribution() / total;
	}

	// --- personal bounties (factionId -> credits) ---

	public static final String KEY_BOUNTIES = "piratepat_bounties";

	@SuppressWarnings("unchecked")
	public static Map<String, Float> bounties() {
		Object val = Global.getSector().getPersistentData().get(KEY_BOUNTIES);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Float>();
			Global.getSector().getPersistentData().put(KEY_BOUNTIES, val);
		}
		return (Map<String, Float>) val;
	}

	public static float getBounty(String factionId) {
		Float val = bounties().get(factionId);
		return val == null ? 0f : val;
	}

	public static float getTotalBounty() {
		float total = 0f;
		for (Float val : bounties().values()) total += val;
		return total;
	}

	/**
	 * Accrue bounty with the faction owning the market where the contribution
	 * happened. Returns true if this crossed the activation threshold.
	 */
	public static boolean addBounty(String factionId, float amount) {
		if (amount <= 0) return false;
		float before = getBounty(factionId);
		float after = before + amount;
		bounties().put(factionId, after);
		float min = PiratePatConfig.bountyActivationMin();
		return before < min && after >= min;
	}

	/** Raise a faction's bounty (e.g. after the player kills its hunters). */
	public static void raiseBounty(String factionId, float amount) {
		if (amount <= 0) return;
		bounties().put(factionId, getBounty(factionId) + amount);
	}

	/** Clear a faction's bounty entirely (paid off). */
	public static void clearBounty(String factionId) {
		bounties().remove(factionId);
	}

	/** Monthly decay; forgotten below 1000 credits. */
	public static void decayBounties() {
		float decay = PiratePatConfig.bountyDecayPerMonth();
		if (decay <= 0) return;
		List<String> remove = new ArrayList<String>();
		for (Map.Entry<String, Float> entry : bounties().entrySet()) {
			float val = entry.getValue() * (1f - decay);
			if (val < 1000f) remove.add(entry.getKey());
			else entry.setValue(val);
		}
		for (String k : remove) bounties().remove(k);
	}

	// --- ledger (preformatted strings; newest first) ---

	@SuppressWarnings("unchecked")
	public static List<String> ledger() {
		Object val = Global.getSector().getPersistentData().get(KEY_LEDGER);
		if (!(val instanceof List)) {
			val = new ArrayList<String>();
			Global.getSector().getPersistentData().put(KEY_LEDGER, val);
		}
		return (List<String>) val;
	}

	public static void addLedger(String text, float amount) {
		String date = Global.getSector().getClock().getShortDate();
		String amountStr = "";
		if (amount > 0) amountStr = ": +" + Misc.getDGSCredits(amount);
		else if (amount < 0) amountStr = ": -" + Misc.getDGSCredits(-amount);
		List<String> ledger = ledger();
		ledger.add(0, date + " - " + text + amountStr);
		while (ledger.size() > LEDGER_MAX_ENTRIES) {
			ledger.remove(ledger.size() - 1);
		}
	}

	// --- rolling net-trade tracker (anti wash-trading) ---
	// Mirrors vanilla PlayerTradeDataForSubmarket: recent buys offset sells of
	// the same goods at the same submarket and vice versa, decaying 50% per
	// econ interval so the memory fades like vanilla's does.

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, Float>> netMap(String key) {
		Object val = Global.getSector().getPersistentData().get(key);
		if (!(val instanceof Map)) {
			val = new LinkedHashMap<String, Map<String, Float>>();
			Global.getSector().getPersistentData().put(key, val);
		}
		return (Map<String, Map<String, Float>>) val;
	}

	private static Map<String, Float> subMap(String key, String submarketKey) {
		Map<String, Map<String, Float>> outer = netMap(key);
		Map<String, Float> inner = outer.get(submarketKey);
		if (inner == null) {
			inner = new LinkedHashMap<String, Float>();
			outer.put(submarketKey, inner);
		}
		return inner;
	}

	/**
	 * Report a sale of qty units of an item; returns the quantity that counts
	 * after netting against recent buys of the same item at the same submarket.
	 */
	public static float countSold(String submarketKey, String itemKey, float qty) {
		Map<String, Float> bought = subMap(KEY_NET_BOUGHT, submarketKey);
		Map<String, Float> sold = subMap(KEY_NET_SOLD, submarketKey);

		float recentBought = bought.containsKey(itemKey) ? bought.get(itemKey) : 0f;
		float counted = Math.max(0f, qty - recentBought);
		float remaining = Math.max(0f, recentBought - qty);
		if (remaining > 0) bought.put(itemKey, remaining);
		else bought.remove(itemKey);

		if (counted > 0) {
			float recentSold = sold.containsKey(itemKey) ? sold.get(itemKey) : 0f;
			sold.put(itemKey, recentSold + counted);
		}
		return counted;
	}

	/** Same as {@link #countSold} with the directions reversed. */
	public static float countBought(String submarketKey, String itemKey, float qty) {
		Map<String, Float> bought = subMap(KEY_NET_BOUGHT, submarketKey);
		Map<String, Float> sold = subMap(KEY_NET_SOLD, submarketKey);

		float recentSold = sold.containsKey(itemKey) ? sold.get(itemKey) : 0f;
		float counted = Math.max(0f, qty - recentSold);
		float remaining = Math.max(0f, recentSold - qty);
		if (remaining > 0) sold.put(itemKey, remaining);
		else sold.remove(itemKey);

		if (counted > 0) {
			float recentBought = bought.containsKey(itemKey) ? bought.get(itemKey) : 0f;
			bought.put(itemKey, recentBought + counted);
		}
		return counted;
	}

	/** Halve all tracked net-trade quantities; drop the dust. */
	public static void decayNetTrade() {
		for (String key : new String[] { KEY_NET_BOUGHT, KEY_NET_SOLD }) {
			Map<String, Map<String, Float>> outer = netMap(key);
			List<String> removeOuter = new ArrayList<String>();
			for (Map.Entry<String, Map<String, Float>> entry : outer.entrySet()) {
				Map<String, Float> inner = entry.getValue();
				List<String> removeInner = new ArrayList<String>();
				for (Map.Entry<String, Float> item : inner.entrySet()) {
					float val = item.getValue() * 0.5f;
					if (val < 0.5f) removeInner.add(item.getKey());
					else item.setValue(val);
				}
				for (String k : removeInner) inner.remove(k);
				if (inner.isEmpty()) removeOuter.add(entry.getKey());
			}
			for (String k : removeOuter) outer.remove(k);
		}
	}
}
