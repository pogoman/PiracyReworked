package piratepat;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.util.Misc;

/**
 * The single transferable receivable that replaces the old per-faction
 * "price on your head". All debt state, all debt arithmetic, and the two
 * halves of a collector encounter that are not dialog work: the forced
 * collection when the player loses, and the write-off when the player
 * destroys the last officer of the crew sent to collect.
 *
 * <p>THE OFFICER RULE, which every combat path funnels into: there is only
 * ever ONE collector fleet, dispatched at full strength, and the contract
 * lives in its officers. Destroy every ship with an officer aboard and the
 * debt dies with them ({@link #reportCollectorEliminated}). Let one get away
 * - or run yourself - and the same crew keeps hunting. Nothing is written up
 * for a kill, nothing escalates, and no second fleet is sent while the first
 * is alive.
 *
 * <p>Everything persists in sector persistent data as PLAIN JDK TYPES ONLY -
 * Float, String, Boolean, Long, Integer. No mod class is ever written into a
 * save from here, because 0.98a-RC8 does not enable XStream's
 * ignoreUnknownElements and a class name welded into a save can never be
 * removed. The same rule applies to every fleet-memory key below: booleans,
 * floats and strings, nothing else.
 *
 * <p>This class is never instantiated. It is the contract the collector
 * manager, the fleet listener, the installment ticker and the dialog command
 * plugin all call into; none of them should keep debt state of their own.
 */
public class PirateDebt {

	public static Logger log = Global.getLogger(PirateDebt.class);

	// --- persistent data keys (sector persistent data; plain JDK types) ---

	/** Float. The TRUE balance in credits. One scalar; there is no per-faction map. */
	public static final String KEY_PRINCIPAL = "piratepat_debtPrincipal";
	/** String. Faction id whose losses generated the claim. Flavour only; may name a dead mod. */
	public static final String KEY_ORIGIN = "piratepat_debtOrigin";
	/** String. Exactly two values: Factions.INDEPENDENT (the house) or Factions.PIRATES. */
	public static final String KEY_HOLDER = "piratepat_debtHolder";
	/** Long. clock.getTimestamp() at open. Drives the notice and the grace period. */
	public static final String KEY_OPENED = "piratepat_debtOpened";
	/** Long. RNG seed, GUARANTEED non-zero - Misc.getRandom(0, n) returns the shared global Random. */
	public static final String KEY_SEED = "piratepat_debtSeed";
	/**
	 * Integer. Times the player cut the comm link on a collector. Feeds only
	 * the surcharge on the pirates' invented quote - nothing about the fleet
	 * sent escalates with it.
	 */
	public static final String KEY_DUCKED = "piratepat_debtDucked";
	/** Integer. Increments once per markup rolled; the level argument to Misc.getRandom. */
	public static final String KEY_QUOTE_INDEX = "piratepat_debtQuoteIndex";
	/** Boolean. A story point forced the figures honest. Permanent for the life of this debt. */
	public static final String KEY_AUDITED = "piratepat_debtAudited";
	/** Boolean. An installment plan is active; collectors stop spawning. */
	public static final String KEY_PLAN = "piratepat_debtPlan";
	/** Float. Installment posted to the current month's report, awaiting month-end reconciliation. */
	public static final String KEY_BOOKED = "piratepat_debtBooked";
	/** Integer. CONSECUTIVE missed payments. Reset to 0 on any full payment. */
	public static final String KEY_MISSED = "piratepat_debtMissed";
	/**
	 * Long. clock.getTimestamp() at which the current enforcement window
	 * OPENED - not a future deadline. The spec called this key
	 * "piratepat_debtEnforceUntil" and stored an absolute future timestamp;
	 * there is no verified API for adding days to a timestamp, whereas
	 * clock.getElapsedDaysSince(long) is documented, so the key stores the
	 * start and isEnforcing() measures elapsed days against
	 * debtEnforcementDays(). 0 means never enforced.
	 */
	public static final String KEY_ENFORCE_SINCE = "piratepat_debtEnforceSince";
	/** Boolean. The written collection notice has been sent. */
	public static final String KEY_NOTICE_SENT = "piratepat_debtNoticeSent";
	/**
	 * Boolean. The debt system was switched off while a balance was open.
	 * Set by the collector manager while it idles disabled and consumed the
	 * first time it runs enabled again, when the notice and grace clocks are
	 * restarted - a player who switches the system back on after months is
	 * warned and given time exactly as if the debt had just opened, instead
	 * of being met by a collector on the next roll.
	 */
	public static final String KEY_DORMANT = "piratepat_debtDormant";
	/**
	 * Boolean. One-shot bounty-to-debt migration guard. Set AFTER the balance
	 * is transferred, never before. clear() deliberately does NOT remove this
	 * (critique F6): paying off a debt must not un-guard the migration.
	 */
	public static final String KEY_MIGRATED_V1 = "piratepat_debtMigratedV1";

	// --- fleet memory keys (serialized inside a CampaignFleetAPI; primitives only) ---

	/**
	 * Boolean, permanent. Marks a live collector. The rules.csv gate, and the
	 * ONLY handle for rediscovering collectors after a load - the manager
	 * scans locations for it instead of holding serialized fleet references.
	 * A settled collector loses this key (critique F8) so it stops counting
	 * against debtCollectorMaxFleets while it flies home.
	 */
	public static final String COLLECTOR_KEY = "$piratepat_collector";
	/** Float, permanent. This collector's frozen lie multiplier. 1.0 when honest. */
	public static final String MARKUP_KEY = "$piratepat_markup";
	/** Boolean, permanent. Paid here - stand down and go home. */
	public static final String SETTLED_KEY = "$piratepat_settled";
	/**
	 * Boolean, permanent. This fleet's elimination is already counted. The
	 * defeat trigger and the fleet listener's battle backstop both see the
	 * same fleet; whichever runs second must do nothing.
	 */
	public static final String COUNTED_KEY = "$piratepat_counted";
	/** Boolean, permanent. The duck counter already incremented for this fleet. */
	public static final String DUCKED_KEY = "$piratepat_ducked";
	/** Boolean, dialog-scoped (= true 0). BeginFleetEncounter re-entry guard AND the OpenCommLink gate. */
	public static final String HANDLED_KEY = "$piratepat_debtHandled";
	/**
	 * Boolean, dialog-scoped (= true 0). Set by the engagement listener the
	 * instant it decides the player lost a stand-up fight, so the
	 * reportBattleOccurred backstop can tell a genuine loss from a clean
	 * disengage - which battle.wasFleetVictorious cannot.
	 */
	public static final String LOST_FIGHT_KEY = "$piratepat_lostFight";
	/** Boolean, dialog-scoped (= true 0). One seizure per encounter, whichever path gets there first. */
	public static final String SEIZED_KEY = "$piratepat_seized";

	/**
	 * The one reason string for every flag this mod sets on a collector, so a
	 * stand-down lifts the whole set in one pass. Nothing else in the mod may
	 * reuse it on the same fleet.
	 */
	public static final String FLAG_REASON = "piratepat_debt";

	private PirateDebt() {
	}

	// ------------------------------------------------------------------
	// persistent accessors - instanceof guarded, mirroring PiratePatData
	// ------------------------------------------------------------------

	private static Map<String, Object> data() {
		return Global.getSector().getPersistentData();
	}

	/** Number rather than Float so a Double from a hand-edited save still reads. */
	private static float getF(String key) {
		Object val = data().get(key);
		if (val instanceof Number) return ((Number) val).floatValue();
		return 0f;
	}

	private static int getI(String key) {
		Object val = data().get(key);
		if (val instanceof Number) return ((Number) val).intValue();
		return 0;
	}

	private static long getL(String key) {
		Object val = data().get(key);
		if (val instanceof Number) return ((Number) val).longValue();
		return 0L;
	}

	private static boolean getB(String key) {
		Object val = data().get(key);
		return val instanceof Boolean && (Boolean) val;
	}

	private static String getS(String key) {
		Object val = data().get(key);
		if (val instanceof String) return (String) val;
		return null;
	}

	private static void put(String key, Object value) {
		data().put(key, value);
	}

	private static CampaignClockAPI clock() {
		return Global.getSector().getClock();
	}

	// ------------------------------------------------------------------
	// state reads
	// ------------------------------------------------------------------

	/** True when a balance is actually outstanding. Pure state - ignores config. */
	public static boolean hasDebt() {
		return principal() >= 1f;
	}

	/** hasDebt() plus both master switches. This is the gate rules.csv should ask about. */
	public static boolean isLive() {
		return PiratePatConfig.enabled() && PiratePatConfig.debtEnabled() && hasDebt();
	}

	/** The TRUE balance, never negative. */
	public static float principal() {
		float val = getF(KEY_PRINCIPAL);
		return val < 0f ? 0f : val;
	}

	/** Factions.INDEPENDENT or Factions.PIRATES; INDEPENDENT when nothing is open yet. */
	public static String holder() {
		String val = getS(KEY_HOLDER);
		return val == null ? Factions.INDEPENDENT : val;
	}

	/** One source of truth for "the underworld owns your paper now". */
	public static boolean isPirateHeld() {
		return Factions.PIRATES.equals(holder());
	}

	/** Faction id the claim originated with. May be null, and may name a removed mod. */
	public static String origin() {
		return getS(KEY_ORIGIN);
	}

	/** May be null - a faction from an uninstalled mod resolves to nothing. */
	public static FactionAPI originFaction() {
		String id = origin();
		if (id == null) return null;
		return Global.getSector().getFaction(id);
	}

	/** May be null in principle; INDEPENDENT and PIRATES always exist in practice. */
	public static FactionAPI holderFaction() {
		return Global.getSector().getFaction(holder());
	}

	/**
	 * The faction a collector on this debt flies under AND draws its hulls
	 * from. Author decision D2: once the paper is sold, collectors are
	 * genuinely PIRATE-faction fleets, not Independent-flagged fronts. One
	 * source of truth so no caller has to re-derive it.
	 */
	public static String collectorFactionId() {
		return holder();
	}

	public static long opened() {
		return getL(KEY_OPENED);
	}

	/** Days since the debt opened. 0 when nothing is open. */
	public static float daysOpen() {
		long ts = opened();
		if (ts == 0L) return 0f;
		return clock().getElapsedDaysSince(ts);
	}

	/** Guaranteed non-zero while a debt is open. */
	public static long seed() {
		return getL(KEY_SEED);
	}

	public static int ducked() {
		return getI(KEY_DUCKED);
	}

	public static int quoteIndex() {
		return getI(KEY_QUOTE_INDEX);
	}

	/** Permanent for the life of this debt. A new debt gets fresh lies. */
	public static boolean isAudited() {
		return getB(KEY_AUDITED);
	}

	public static boolean hasPlan() {
		return getB(KEY_PLAN);
	}

	/** What the current month's report has booked against the plan, pre-reconciliation. */
	public static float getBooked() {
		return getF(KEY_BOOKED);
	}

	/** Consecutive missed installments. */
	public static int missed() {
		return getI(KEY_MISSED);
	}

	public static boolean isNoticeSent() {
		return getB(KEY_NOTICE_SENT);
	}

	/** The one-shot bounty-to-debt migration guard. */
	public static boolean isMigrated() {
		return getB(KEY_MIGRATED_V1);
	}

	/** A balance is on the books but the system is switched off around it. */
	public static boolean isDormant() {
		return getB(KEY_DORMANT);
	}

	/**
	 * The system is running switched off with a balance open. Idempotent and
	 * cheap enough to call every tick. Nothing else changes: interest stops
	 * (the ticker is gated), collectors stand down (the manager's job), and
	 * the balance simply waits.
	 */
	public static void markDormant() {
		if (!hasDebt() || isDormant()) return;
		put(KEY_DORMANT, true);
		log.info("Debt system switched off with " + (int) principal()
				+ " outstanding; the balance is dormant");
	}

	/**
	 * The system is running enabled again. If a balance slept through the
	 * off period its clocks restart from now: the written notice goes out
	 * again on day N and nothing is dispatched before the grace period, and
	 * any enforcement window is forgotten.
	 *
	 * @return true if a dormant debt was woken by this call
	 */
	public static boolean wakeIfDormant() {
		if (!isDormant()) return false;
		data().remove(KEY_DORMANT);
		if (!hasDebt()) return false;
		put(KEY_OPENED, clock().getTimestamp());
		put(KEY_NOTICE_SENT, false);
		put(KEY_ENFORCE_SINCE, 0L);
		log.info("Debt system switched back on with " + (int) principal()
				+ " outstanding; notice and grace periods restart from today");
		return true;
	}

	/**
	 * While enforcing, the manager halves its check interval and doubles the
	 * spawn probability. Measured as elapsed days since the window opened, so
	 * a config change takes effect immediately.
	 */
	public static boolean isEnforcing() {
		long since = getL(KEY_ENFORCE_SINCE);
		if (since == 0L) return false;
		return clock().getElapsedDaysSince(since) < PiratePatConfig.debtEnforcementDays();
	}

	/** Monthly compounding rate for the CURRENT holder. */
	public static float monthlyRate() {
		return isPirateHeld()
				? PiratePatConfig.debtPirateInterestPerMonth()
				: PiratePatConfig.debtInterestPerMonth();
	}

	// ------------------------------------------------------------------
	// seeded randomness
	// ------------------------------------------------------------------

	/**
	 * A seed that is never zero. Misc.getRandom(0, n) returns the shared
	 * global Random (Misc.java:2973), which would silently destroy
	 * determinism: a quoted figure has to survive save/reload byte-identical.
	 */
	public static long nonZeroSeed() {
		Random bootstrap = new Random();
		long val = bootstrap.nextLong();
		while (val == 0L) {
			val = bootstrap.nextLong();
		}
		return val;
	}

	/**
	 * The debt's own RNG stream at the given level. Same debt + same level
	 * always yields the same numbers, across saves and reloads.
	 */
	public static Random rng(int level) {
		return Misc.getRandom(seed(), level);
	}

	// ------------------------------------------------------------------
	// eligibility
	// ------------------------------------------------------------------

	/**
	 * Whether a faction's losses can become a receivable somebody will buy.
	 *
	 * <p>Critique F5: this predicate is deliberately EXACTLY as wide as
	 * CommissionIntel's victim gate (CommissionIntel.java:229/:246/:269 -
	 * not player, not a pirate faction, inside the underworld economy) and
	 * as BlackMarketListener's accrual gate. If it were any narrower, a
	 * commission raid against a luddic_path colony would silently accrue
	 * zero debt while the intel text kept telling the player their paper had
	 * grown.
	 *
	 * <p>In particular it does NOT consult the "postsNoBounties" faction flag.
	 * A receivable is not a bounty: it is bought and collected by a third
	 * party, so whether the victim would post a bounty of their own is
	 * irrelevant. Machine and outsider factions (Threat, Remnants, Omega,
	 * Dwellers, derelicts) are excluded because nothing they lose can be
	 * fenced, which is the same test the tithe uses.
	 */
	public static boolean canOriginateDebt(FactionAPI faction) {
		if (faction == null) return false;
		if (faction.isPlayerFaction()) return false;
		if (Misc.isPirateFaction(faction)) return false;
		return !UnderworldTithe.isOutsideUnderworldEconomy(faction);
	}

	/** Convenience overload; an unresolvable faction id is never eligible. */
	public static boolean canOriginateDebt(String factionId) {
		if (factionId == null) return false;
		return canOriginateDebt(Global.getSector().getFaction(factionId));
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	/**
	 * Add to the balance, opening a brand-new debt if none is outstanding.
	 * The collection house consolidates: smuggling through a second faction
	 * raises the same balance rather than opening a second claim.
	 *
	 * <p>Writes no ledger line and posts no message - the caller owns the
	 * flavour, because only the caller knows whether this was a black market
	 * run or a commissioned raid.
	 *
	 * @param factionId the victim; ignored unless canOriginateDebt passes
	 * @param amount credits; non-positive is a no-op
	 * @return true if this call OPENED a new debt (so the caller can say so)
	 */
	public static boolean accrue(String factionId, float amount) {
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return false;
		if (amount <= 0f) return false;
		if (!canOriginateDebt(factionId)) {
			if (PiratePatConfig.debugLogging()) {
				log.info("Debt accrual refused for ineligible faction " + factionId
						+ " (" + (int) amount + " credits)");
			}
			return false;
		}

		boolean opening = !hasDebt();
		if (opening) {
			openInternal(factionId);
		}
		put(KEY_PRINCIPAL, principal() + amount);
		if (PiratePatConfig.debugLogging()) {
			log.info("Debt accrued " + (int) amount + " from " + factionId
					+ "; balance now " + (int) principal() + ", holder " + holder());
		}
		return opening;
	}

	/**
	 * Open the books. Called on the first accrual and by the one-shot bounty
	 * migration; resets every per-debt counter so a repeat offender starts a
	 * clean arc with fresh lies.
	 */
	private static void openInternal(String originFactionId) {
		put(KEY_ORIGIN, originFactionId);
		put(KEY_HOLDER, Factions.INDEPENDENT);
		put(KEY_OPENED, clock().getTimestamp());
		put(KEY_SEED, nonZeroSeed());
		put(KEY_QUOTE_INDEX, 0);
		put(KEY_DUCKED, 0);
		put(KEY_MISSED, 0);
		put(KEY_BOOKED, 0f);
		put(KEY_AUDITED, false);
		put(KEY_PLAN, false);
		put(KEY_NOTICE_SENT, false);
		put(KEY_ENFORCE_SINCE, 0L);
		put(KEY_PRINCIPAL, 0f);
		data().remove(KEY_DORMANT);
	}

	/**
	 * The one-shot save migration entry point: consolidate an old save's
	 * per-faction bounties into a single receivable. "opened" is set to NOW,
	 * so a migrated player still gets the full notice period and grace period
	 * and is never ambushed on their first load.
	 *
	 * <p>Unlike accrue() this does NOT test canOriginateDebt on the origin -
	 * an existing save can legitimately hold a luddic_path bounty, and
	 * dropping the balance because the origin is merely flavour would destroy
	 * the player's state. Pass Factions.INDEPENDENT when no eligible origin
	 * can be determined.
	 *
	 * @return true if a debt was opened
	 */
	public static boolean migrateOpen(String originFactionId, float amount) {
		if (amount <= 0f) return false;
		if (hasDebt()) return false;
		openInternal(originFactionId == null ? Factions.INDEPENDENT : originFactionId);
		put(KEY_PRINCIPAL, amount);
		log.info("Migrated legacy bounties into a single debt of " + (int) amount
				+ ", origin " + originFactionId);
		return true;
	}

	/** Set the true balance outright. Clamped at zero; does not settle. */
	public static void setPrincipal(float value) {
		put(KEY_PRINCIPAL, value < 0f ? 0f : value);
	}

	/** Add to the true balance without any eligibility test (fees, interest, migration). */
	public static void addPrincipal(float amount) {
		if (amount <= 0f) return;
		setPrincipal(principal() + amount);
	}

	/**
	 * Reduce the true balance by credits actually collected, and settle
	 * everything if that clears it. This is the ONE path that turns a payment
	 * into a settlement, so no caller has to remember to check for zero.
	 *
	 * @return the amount actually written off (never more than was owed)
	 */
	public static float reduceBalance(float amount) {
		if (amount <= 0f) return 0f;
		float before = principal();
		float applied = Math.min(before, amount);
		setPrincipal(before - applied);
		if (principal() < 1f) settleAndClear();
		return applied;
	}

	/** Apply one month of the holder's interest. Returns the interest added. */
	public static float applyInterest() {
		if (!hasDebt()) return 0f;
		float rate = monthlyRate();
		if (rate <= 0f) return 0f;
		float interest = principal() * rate;
		addPrincipal(interest);
		return interest;
	}

	/**
	 * Close the books: green message, ledger line, every key wiped, every live
	 * collector stood down.
	 */
	public static void settleAndClear() {
		// nothing on the books: never announce a settlement that did not happen
		if (data().get(KEY_HOLDER) == null && data().get(KEY_PRINCIPAL) == null) return;
		PiratePatData.addLedger("The receivable in your name is closed out", 0f);
		MessageIntel msg = new MessageIntel();
		msg.addLine("Your debt is settled.", Misc.getPositiveHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Nobody is coming to collect.", Misc.getTextColor());
		Global.getSector().getCampaignUI().addMessage(msg);
		clear();
		settleAllCollectors();
	}

	/**
	 * Wipe every debt key.
	 *
	 * <p>Critique F6: piratepat_debtMigratedV1 is deliberately NOT removed.
	 * It guards a one-shot save migration, not the debt, and un-guarding it
	 * on payoff would re-run the migration on the next load.
	 */
	public static void clear() {
		data().remove(KEY_PRINCIPAL);
		data().remove(KEY_ORIGIN);
		data().remove(KEY_HOLDER);
		data().remove(KEY_OPENED);
		data().remove(KEY_SEED);
		data().remove(KEY_DUCKED);
		data().remove(KEY_QUOTE_INDEX);
		data().remove(KEY_AUDITED);
		data().remove(KEY_PLAN);
		data().remove(KEY_BOOKED);
		data().remove(KEY_MISSED);
		data().remove(KEY_ENFORCE_SINCE);
		data().remove(KEY_NOTICE_SENT);
		data().remove(KEY_DORMANT);
		// kill counters from before the officer rule; plain Integers, harmless
		// in a save, but a closed debt should leave nothing behind
		data().remove("piratepat_debtWins");
		data().remove("piratepat_debtPirateWins");
		// KEY_MIGRATED_V1 stays - see javadoc
	}

	// ------------------------------------------------------------------
	// the collection notice
	// ------------------------------------------------------------------

	/**
	 * The day-15 letter. Idempotent: once sent it never fires again for this
	 * debt.
	 *
	 * <p>Critique F3: NO MessageClickAction. The income tab has nothing to do
	 * with a collection notice, and its reachability for a colony-less player
	 * is an open question. A plain message is correct and cannot mislead.
	 *
	 * @return true if the notice was sent by this call
	 */
	public static boolean sendCollectionNotice() {
		if (!isLive()) return false;
		if (isNoticeSent()) return false;
		put(KEY_NOTICE_SENT, true);

		MessageIntel msg = new MessageIntel();
		msg.addLine("A collection notice catches up with you", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "A collection house has bought up losses attributed "
				+ "to you and intends to collect.", Misc.getTextColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Outstanding: %s", Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(principal()) }, Misc.getHighlightColor());
		FactionAPI indep = Global.getSector().getFaction(Factions.INDEPENDENT);
		if (indep != null) msg.setIcon(indep.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg);

		PiratePatData.addLedger("A collection notice catches up with you", 0f);
		return true;
	}

	// ------------------------------------------------------------------
	// quoting - the lie
	// ------------------------------------------------------------------

	/**
	 * Roll this collector's frozen lie multiplier. Called ONCE per collector,
	 * at spawn, and stored in fleet memory under MARKUP_KEY.
	 *
	 * <p>The MULTIPLIER is frozen, not the figure: the balance keeps accruing
	 * between spawn and interception, and a frozen absolute could end up
	 * quoting less than the player owes.
	 *
	 * <p>Seeded off the debt's own stream at an index that advances with each
	 * roll, so save/reload in front of a collector quotes the same number.
	 *
	 * @return 1.0 when the quote must be honest (creditor-held, or audited)
	 */
	public static float rollMarkup() {
		if (!isPirateHeld() || isAudited()) return 1f;
		int index = quoteIndex() + 1;
		put(KEY_QUOTE_INDEX, index);

		float min = PiratePatConfig.debtPirateMarkupMin();
		float max = PiratePatConfig.debtPirateMarkupMax();
		if (max < min) max = min;
		float markup = rng(index).nextFloat() * (max - min) + min;
		// every link cut is priced into the next lie; there is no cap, because
		// the figure is invented anyway and a story point reads it back down
		markup += PiratePatConfig.debtDuckSurcharge() * ducked();
		if (markup < 1f) markup = 1f;
		return markup;
	}

	/**
	 * The figure a collector reads at the player.
	 *
	 * <p>Inflated quotes are rounded to suspiciously round numbers - that
	 * rounding IS the tell. An honest quote is never rounded, because the
	 * exact figure is the point.
	 */
	public static float quotedFor(float markup) {
		float real = principal();
		if (real <= 0f) return 0f;
		if (markup <= 1f) return real;

		float quoted = real * markup;
		float step = real < 100000f ? 5000f : 25000f;
		quoted = Math.round(quoted / step) * step;
		if (quoted < real) quoted = real;
		return quoted;
	}

	/**
	 * This collector's frozen multiplier, with every honesty override applied.
	 * A collector that predates the audit still quotes honestly, because the
	 * audit is permanent for the debt, not for the fleet.
	 */
	public static float markupOn(CampaignFleetAPI collector) {
		if (collector == null) return 1f;
		if (!isPirateHeld() || isAudited()) return 1f;
		float markup = collector.getMemoryWithoutUpdate().getFloat(MARKUP_KEY);
		return markup < 1f ? 1f : markup;
	}

	/** The figure THIS collector quotes. Convenience for the dialog. */
	public static float quotedBy(CampaignFleetAPI collector) {
		return quotedFor(markupOn(collector));
	}

	/**
	 * A story point forced the figures honest - permanently, for the life of
	 * this debt. Also drops the challenged collector's own frozen multiplier
	 * so the rebuilt option list quotes the corrected figure immediately.
	 */
	public static void audit(CampaignFleetAPI collector) {
		put(KEY_AUDITED, true);
		if (collector != null) {
			collector.getMemoryWithoutUpdate().set(MARKUP_KEY, 1f);
		}
		PiratePatData.addLedger("You read the original schedule back at your creditors", 0f);
	}

	/** Audit with no fleet in hand. */
	public static void audit() {
		audit(null);
	}

	// ------------------------------------------------------------------
	// the sale
	// ------------------------------------------------------------------

	/**
	 * The house cuts its losses. The PRINCIPAL IS UNCHANGED - they sold at a
	 * discount and that is their loss - but the interest rate doubles, the
	 * RNG stream is replaced (the pirates' arithmetic is not a continuation
	 * of the house's ledger), and every future quote is a lie until a story
	 * point is spent.
	 *
	 * <p>Two roads lead here. The terms road: the player signed and stopped
	 * paying (recordMiss). And, only with debtSellOnHouseDefeat switched on,
	 * the officer road: the player destroyed the house's crew and the house
	 * would rather sell than write off (reportCollectorEliminated). With the
	 * switch off - the default - that second road ends the debt instead.
	 *
	 * @param reason short phrase for the ledger, e.g. "three missed payments"
	 * @return false if the pirates already hold it
	 */
	public static boolean sellToPirates(String reason) {
		if (!hasDebt()) return false;
		if (isPirateHeld()) return false;

		put(KEY_HOLDER, Factions.PIRATES);
		put(KEY_SEED, nonZeroSeed());
		put(KEY_QUOTE_INDEX, 0);
		put(KEY_AUDITED, false);

		PiratePatData.addLedger("Your paper is sold at a discount - " + reason, 0f);

		MessageIntel msg = new MessageIntel();
		msg.addLine("Your debt has changed hands", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Sold to people who do not keep books.",
				Misc.getTextColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Balance unchanged at %s; the terms are not.",
				Misc.getTextColor(), new String[] { Misc.getDGSCredits(principal()) },
				Misc.getHighlightColor());
		FactionAPI pirates = Global.getSector().getFaction(Factions.PIRATES);
		if (pirates != null) msg.setIcon(pirates.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg);

		log.info("Debt sold to pirates: " + reason + "; balance " + (int) principal());
		return true;
	}

	// ------------------------------------------------------------------
	// combat outcomes
	// ------------------------------------------------------------------

	/**
	 * Ships in a collector fleet that still have an officer aboard: the
	 * commander's flagship and every officered hull FleetFactoryV3 handed out
	 * at spawn. The contract lives in these people. While any of them is
	 * alive the crew keeps hunting; when the last of them dies there is
	 * nobody left to collect.
	 *
	 * <p>Counted off the LIVE member list, never off a spawn-time snapshot,
	 * which is correct across a flagship loss because vanilla does not move
	 * the commander onto a new hull when theirs dies - PersonBountyIntel pays
	 * out on exactly that. Fighter wings and default (all-zero) captains
	 * never count. Zero for a fleet with no ships at all.
	 */
	public static int officersRemaining(CampaignFleetAPI fleet) {
		if (fleet == null || fleet.getFleetData() == null) return 0;
		int count = 0;
		for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
			if (member == null || member.isFighterWing()) continue;
			PersonAPI captain = member.getCaptain();
			if (captain == null || captain.isDefault()) continue;
			count++;
		}
		return count;
	}

	/**
	 * Whether the fleet commander still has a hull under them. Narration
	 * only - the officer rule counts every officer, not just the captain.
	 */
	public static boolean commanderAboard(CampaignFleetAPI fleet) {
		if (fleet == null || fleet.getFleetData() == null) return false;
		PersonAPI commander = fleet.getCommander();
		if (commander == null || commander.isDefault()) return false;
		return fleet.getFleetData().getMemberWithCaptain(commander) != null;
	}

	/**
	 * THE OFFICER RULE: the player has destroyed every officered ship in a
	 * collector fleet, and nobody is left who holds the contract. The debt
	 * dies with them - or, with debtSellOnHouseDefeat on and the house still
	 * holding the paper, the house sells to the pirates rather than write it
	 * off, and it is their crew whose officers have to die.
	 *
	 * <p>Reached from two places that can both see the same fleet: the
	 * PiratepatCollectorBeaten defeat trigger (in the dialog, narrated by the
	 * caller) and the fleet listener's reportBattleOccurred and despawn
	 * backstops (no dialog; the messages posted here are all the player
	 * sees). COUNTED_KEY on the fleet makes whichever runs second a no-op.
	 * A settled crew flying home is nobody's business even if the player
	 * hunts it down; the paper is on terms, not in their hands.
	 *
	 * <p>Nothing is written up. Destroying the collectors is the alternate
	 * ending, not a provocation, and a kill penalty here would make the one
	 * fight the whole system builds toward strictly worse than paying.
	 *
	 * @param collector the fleet whose last officer just died
	 * @param text the live dialog's text panel for the reputation line, or null
	 * @return true if the paper was SOLD rather than written off - the
	 *         caller narrates the difference
	 */
	public static boolean reportCollectorEliminated(CampaignFleetAPI collector,
			TextPanelAPI text) {
		if (collector == null) return false;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return false;

		MemoryAPI fm = collector.getMemoryWithoutUpdate();
		if (fm.getBoolean(COUNTED_KEY)) return false;
		if (fm.getBoolean(SETTLED_KEY)) return false;
		if (!hasDebt()) return false;
		fm.set(COUNTED_KEY, true);

		boolean wasPirateHeld = isPirateHeld();
		applyCollectorKillRep(wasPirateHeld, text);

		if (!wasPirateHeld && PiratePatConfig.debtSellOnHouseDefeat()) {
			// whatever is left of the crew is leaderless and goes home; the
			// pirates send their own people, and they do not wait for the
			// next slow roll to do it
			markSettled(collector);
			boolean sold = sellToPirates("the collectors did not come back");
			put(KEY_ENFORCE_SINCE, clock().getTimestamp());
			log.info("Collector eliminated with the house holding the paper; sold=" + sold);
			return sold;
		}

		writeOff(wasPirateHeld);
		return false;
	}

	/**
	 * Close the books because there is nobody left to collect. The mirror of
	 * settleAndClear: a different message and ledger line, the same wipe and
	 * the same stand-down of anything still flying.
	 *
	 * @param pirateHeld who held the paper when their last officer died
	 */
	public static void writeOff(boolean pirateHeld) {
		PiratePatData.addLedger("The receivable in your name died with the people sent to "
				+ "collect it", 0f);
		MessageIntel msg = new MessageIntel();
		msg.addLine("Your debt is written off", Misc.getPositiveHighlightColor());
		if (pirateHeld) {
			msg.addLine(BaseIntelPlugin.BULLET + "The crew holding your paper is dead to the "
					+ "last officer. Nobody's making money here.", Misc.getTextColor());
		} else {
			msg.addLine(BaseIntelPlugin.BULLET + "The house's collectors are dead to the last "
					+ "officer. No crew will take the contract now.", Misc.getTextColor());
		}
		msg.addLine(BaseIntelPlugin.BULLET + "Nobody is coming to collect.", Misc.getTextColor());
		FactionAPI holder = holderFaction();
		if (holder != null) msg.setIcon(holder.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg);

		log.info("Debt written off: last collector officer destroyed, "
				+ (int) principal() + " credits forgiven");
		clear();
		settleAllCollectors();
	}

	/**
	 * The reputation consequence of killing a collector, which inverts with
	 * the holder (author decision D2 - pirate-held collectors really are
	 * pirates).
	 *
	 * <p>Creditor-held: the underworld appreciates its patron handling
	 * independent collectors, capped at FRIENDLY so kill-farming alone cannot
	 * buy the relationship. Pirate-held: shooting your own creditors costs,
	 * bounded at SUSPICIOUS so it can never wreck the patronage premise.
	 *
	 * <p>Deliberately CUSTOM, never RepActions.COMBAT_NORMAL, whose
	 * ensureAtBest = RepLevel.HOSTILE would drop a COOPERATIVE patron to
	 * HOSTILE on a single win. Vanilla's own COMBAT_NORMAL must be suppressed
	 * separately by Misc.makeNoRepImpact on the collector at spawn.
	 */
	public static void applyCollectorKillRep(boolean pirateHeld, TextPanelAPI text) {
		int points = pirateHeld
				? PiratePatConfig.pirateRepPerPirateCollectorKill()
				: PiratePatConfig.pirateRepPerCollectorKill();
		if (points <= 0) return;

		CustomRepImpact impact = new CustomRepImpact();
		impact.delta = (pirateHeld ? -points : points) * 0.01f;
		impact.limit = pirateHeld ? RepLevel.SUSPICIOUS : RepLevel.FRIENDLY;
		String reason = pirateHeld
				? "Destroyed a collector working the underworld's own paper"
				: "Destroyed collectors pursuing a patron of the underworld";
		Global.getSector().adjustPlayerReputation(
				new RepActionEnvelope(RepActions.CUSTOM, impact, null, text, true, true, reason),
				Factions.PIRATES);
	}

	/** Whether this fleet's elimination has already been consumed by either path. */
	public static boolean isCounted(CampaignFleetAPI collector) {
		if (collector == null) return true;
		return collector.getMemoryWithoutUpdate().getBoolean(COUNTED_KEY);
	}

	/**
	 * Whether the story-point challenge option should be offered at all: the
	 * pirates hold the paper, it has not been audited, and the option has a
	 * non-zero cost. One place, so the option and its wiring can never drift.
	 */
	public static boolean canChallenge() {
		return isPirateHeld() && !isAudited()
				&& PiratePatConfig.debtChallengeStoryPoints() > 0;
	}

	/**
	 * The player cut the comm link. Guarded per fleet, so re-opening comms
	 * with the same collector and cutting again is free - the price is for
	 * ducking a collector, not for closing a window. Running is allowed and
	 * changes nothing about the fleet: the same crew keeps hunting. The only
	 * price is on the pirates' next invented quote.
	 */
	public static void recordDuck(CampaignFleetAPI collector) {
		if (collector == null) return;
		MemoryAPI fm = collector.getMemoryWithoutUpdate();
		if (fm.getBoolean(DUCKED_KEY)) return;
		fm.set(DUCKED_KEY, true);
		put(KEY_DUCKED, ducked() + 1);
	}

	// ------------------------------------------------------------------
	// forced collection - author decision D1
	// ------------------------------------------------------------------

	/**
	 * The collector collects BY FORCE. Called when the player fought a
	 * collector in a stand-up battle and lost: credits first, then cargo by
	 * descending unit value, until the balance is covered or there is nothing
	 * left worth taking. The balance drops by the value seized.
	 *
	 * <p>There is no "you survived but lost" hook in the API and no defeat
	 * trigger for the player - losingPath() fires nothing. The caller detects
	 * the loss in CampaignEventListener.reportPlayerEngagement, where
	 * didPlayerWin() == false is NOT sufficient (a clean disengage reports
	 * ESCAPE_PLAYER_SUCCESS with the AI as winner): both engagement results
	 * must have FleetGoal.ATTACK.
	 *
	 * <p>What is never taken: people, AI cores and anything else tagged
	 * no_loss_from_combat, special items unless debtSeizeSpecialItems is on,
	 * and a survival floor of supplies and fuel - a seizure in deep space
	 * must not be a soft-lock. Cargo is credited against the balance at
	 * debtSeizeCargoFenceRate of base value, because a hold full of goods is
	 * worth less to a collector than the same figure in cash.
	 *
	 * <p>Caller must guard idempotency with SEIZED_KEY on the collector -
	 * this method does not, because the backstop path needs to set that flag
	 * before it knows whether anything will be taken.
	 *
	 * @param collector the collector, for the stand-down if this clears the balance
	 * @param text the live dialog's text panel, or null when there is none
	 *             (autoresolve); with null the caller should reportSeizure()
	 * @return credits-equivalent actually seized; 0 if nothing was taken
	 */
	public static float seizeByForce(CampaignFleetAPI collector, TextPanelAPI text) {
		if (!PiratePatConfig.enabled() || !PiratePatConfig.debtEnabled()) return 0f;
		if (!PiratePatConfig.debtSeizeEnabled()) return 0f;

		float owed = principal();
		if (owed < 1f) return 0f;

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		// a total defeat replaces the player fleet object via the respawn
		// dialog - there is nothing to take and the reference goes stale
		if (player == null || !player.isValidPlayerFleet()) return 0f;

		CargoAPI cargo = player.getCargo();
		if (cargo == null) return 0f;

		float taken = 0f;

		// 1. credits. MutableValue.subtract does NOT clamp at zero; every
		// vanilla caller clamps by hand and so do we.
		float cash = Math.min(cargo.getCredits().get(), owed);
		if (cash >= 1f) {
			cargo.getCredits().subtract(cash);
			if (cargo.getCredits().get() < 0f) cargo.getCredits().set(0f);
			taken += cash;
			if (text != null) AddRemoveCommodity.addCreditsLossText((int) cash, text);
		}

		// 2. cargo, most valuable per unit first. getStacksCopy copies the
		// LIST but hands back live stacks, so snapshot everything needed
		// before the first removeItems call.
		List<CargoStackAPI> stacks = new ArrayList<CargoStackAPI>(cargo.getStacksCopy());
		Collections.sort(stacks, new Comparator<CargoStackAPI>() {
			public int compare(CargoStackAPI a, CargoStackAPI b) {
				return b.getBaseValuePerUnit() - a.getBaseValuePerUnit();
			}
		});

		float fence = PiratePatConfig.debtSeizeCargoFenceRate();
		if (fence < 0f) fence = 0f;
		float remaining = owed - taken;
		for (CargoStackAPI stack : stacks) {
			if (remaining < 1f) break;
			if (stack == null || stack.isNull() || stack.getSize() < 1f) continue;
			if (stack.isPersonnelStack()) continue;
			if (stack.isSpecialStack() && !PiratePatConfig.debtSeizeSpecialItems()) continue;
			if (stack.isCommodityStack()) {
				CommoditySpecAPI spec = stack.getResourceIfResource();
				if (spec != null && spec.hasTag(Commodities.TAG_NO_LOSS_FROM_COMBAT)) continue;
			}

			float perUnit = stack.getBaseValuePerUnit() * fence;
			if (perUnit < 1f) continue;

			float available = stack.getSize() - survivalFloorFor(stack);
			if (available < 1f) continue;

			int units = (int) Math.min(available, Math.ceil(remaining / perUnit));
			if (units < 1) continue;

			boolean isCommodity = stack.isCommodityStack();
			String commodityId = stack.getCommodityId();
			String display = stack.getDisplayName();

			cargo.removeItems(stack.getType(), stack.getData(), units);

			float value = units * perUnit;
			remaining -= value;
			taken += value;

			if (text != null) {
				if (isCommodity && commodityId != null) {
					AddRemoveCommodity.addCommodityLossText(commodityId, units, text);
				} else {
					text.addPara("Lost " + units + "x " + display,
							Misc.getNegativeHighlightColor());
				}
			}
		}
		cargo.removeEmptyStacks();
		cargo.sort();

		if (taken < 1f) {
			if (text != null) {
				text.addPara("Boarding parties go through your holds compartment by compartment "
						+ "and find nothing worth the shuttle fuel. The balance stands.");
			}
			return 0f;
		}

		reduceBalance(taken);

		if (text != null) {
			text.addPara("Nobody reads you a figure. They simply take until the ledger balances: "
					+ "%s written off, %s still owed.",
					Misc.getHighlightColor(),
					new String[] { Misc.getDGSCredits(taken), Misc.getDGSCredits(principal()) });
		}

		// reduceBalance already settles everything if this cleared the debt;
		// this covers the collector standing in front of us either way
		if (!hasDebt()) markSettled(collector);

		log.info("Collector seized " + (int) taken + " by force; balance now "
				+ (int) principal());
		return taken;
	}

	/**
	 * Units of a stack that are never taken, so a seizure can never strand the
	 * player. Fuel and supplies only - everything else is fair game.
	 */
	public static float survivalFloorFor(CargoStackAPI stack) {
		if (stack == null) return 0f;
		if (stack.isFuelStack()) return PiratePatConfig.debtSeizeFuelFloor();
		if (stack.isSupplyStack()) return PiratePatConfig.debtSeizeSupplyFloor();
		return 0f;
	}

	/**
	 * Report a seizure through the message log. Used by the autoresolve
	 * backstop, where there is no dialog text panel to write into.
	 */
	public static void reportSeizure(float taken) {
		if (taken < 1f) return;
		MessageIntel msg = new MessageIntel();
		msg.addLine("Collectors took payment", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Seized against the balance: %s", Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(taken) }, Misc.getHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Balance remaining: %s", Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(principal()) }, Misc.getHighlightColor());
		Global.getSector().getCampaignUI().addMessage(msg);
		PiratePatData.addLedger("Collectors took payment out of your holds", taken);
	}

	// ------------------------------------------------------------------
	// standing collectors down
	// ------------------------------------------------------------------

	/**
	 * The ONE stand-down. Nothing else in the mod may write its own: a
	 * collector that is stood down must lose every flag the spawn applied, in
	 * one place, or it keeps hunting or keeps being counted.
	 *
	 * <p>Critique F8: a settled collector must lose COLLECTOR_KEY as well as
	 * gaining SETTLED_KEY, or the manager's rediscovery scan keeps counting it
	 * against debtCollectorMaxFleets while it flies home.
	 *
	 * <p>Hostility is cleared both by reason AND by bare key, because
	 * Misc.isFleetMadeHostileToFaction tests contains() rather than the value,
	 * and another mod may have set the bare flag with no reason at all.
	 * NON_HOSTILE_OVERRIDES_MAKE_HOSTILE is then set as belt and braces, since
	 * MAKE_HOSTILE otherwise outranks MAKE_NON_HOSTILE.
	 */
	public static void markSettled(CampaignFleetAPI collector) {
		if (collector == null) return;
		MemoryAPI mem = collector.getMemoryWithoutUpdate();

		mem.unset(COLLECTOR_KEY);
		mem.set(SETTLED_KEY, true);

		Misc.clearFlag(mem, MemFlags.MEMORY_KEY_MAKE_HOSTILE);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_HOSTILE);
		mem.unset(MemFlags.MEMORY_KEY_MAKE_HOSTILE + "_" + Factions.PLAYER);
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE, FLAG_REASON, false, 0f);
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_PREVENT_DISENGAGE,
				FLAG_REASON, false, 0f);
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_PURSUE_PLAYER, FLAG_REASON, false, 0f);
		Misc.setFlagWithReason(mem, MemFlags.FLEET_BUSY, FLAG_REASON, false, 0f);
		mem.unset(MemFlags.FLEET_SPECIAL_ACTION);
		mem.set(MemFlags.MEMORY_KEY_MAKE_ALLOW_DISENGAGE, true);
		mem.set(MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, true);
		mem.set(MemFlags.NON_HOSTILE_OVERRIDES_MAKE_HOSTILE, true);
		mem.unset(MemFlags.MEMORY_KEY_IGNORE_PLAYER_COMMS);

		if (collector.getAI() instanceof ModularFleetAIAPI) {
			ModularFleetAIAPI ai = (ModularFleetAIAPI) collector.getAI();
			if (ai.getTacticalModule() != null) {
				ai.getTacticalModule().setTarget(null);
				ai.getTacticalModule().forceTargetReEval();
			}
		}
		Misc.giveStandardReturnToSourceAssignments(collector, true);
	}

	/**
	 * Stand down every live collector. Called when the balance clears.
	 * Rediscovers them by scanning locations for COLLECTOR_KEY - the mod never
	 * holds a serialized CampaignFleetAPI reference.
	 *
	 * @return how many fleets were stood down
	 */
	public static int settleAllCollectors() {
		int count = 0;
		for (LocationAPI loc : Global.getSector().getAllLocations()) {
			for (CampaignFleetAPI fleet
					: new ArrayList<CampaignFleetAPI>(loc.getFleets())) {
				if (fleet == null) continue;
				if (!fleet.getMemoryWithoutUpdate().getBoolean(COLLECTOR_KEY)) continue;
				markSettled(fleet);
				count++;
			}
		}
		if (count > 0 && PiratePatConfig.debugLogging()) {
			log.info("Stood down " + count + " collector fleet(s)");
		}
		return count;
	}

	// ------------------------------------------------------------------
	// payment and terms
	// ------------------------------------------------------------------

	/**
	 * The player agrees to settle a quoted figure. Takes what they are
	 * carrying, up to the quote, and puts the rest on terms.
	 *
	 * <p>THE CRUEL RULE: the remainder of the QUOTE becomes the new true
	 * principal. Taking terms on an inflated pirate figure makes the lie
	 * real, permanently. This is fair only because the true balance is on
	 * screen in highlight colour throughout - do not soften it.
	 *
	 * <p>Always available, even at zero credits: the plan then opens on the
	 * whole figure.
	 *
	 * @param quoted the figure the collector read out (quotedBy(collector))
	 * @param text dialog text panel for the credits-lost line, or null
	 * @return the remaining balance; 0 means fully settled
	 */
	public static float agreeToPay(float quoted, TextPanelAPI text) {
		if (quoted <= 0f) return 0f;

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		float paid = 0f;
		if (player != null && player.getCargo() != null) {
			CargoAPI cargo = player.getCargo();
			paid = Math.min(cargo.getCredits().get(), quoted);
			if (paid >= 1f) {
				cargo.getCredits().subtract(paid);
				if (cargo.getCredits().get() < 0f) cargo.getCredits().set(0f);
				if (text != null) AddRemoveCommodity.addCreditsLossText((int) paid, text);
			} else {
				paid = 0f;
			}
		}

		float remaining = quoted - paid;
		if (remaining < 1f) {
			PiratePatData.addLedger("Settled the receivable in your name", paid);
			settleAndClear();
			return 0f;
		}

		openPlan(remaining);
		if (paid >= 1f) {
			PiratePatData.addLedger("Paid down the receivable in your name and signed for the rest",
					paid);
		} else {
			PiratePatData.addLedger("Signed for the receivable in your name", 0f);
		}
		return remaining;
	}

	/** Convenience: no dialog text panel. */
	public static float agreeToPay(float quoted) {
		return agreeToPay(quoted, null);
	}

	/**
	 * Put the balance on monthly terms. Sets the remainder as the new true
	 * principal (so an unchallenged inflated quote is booked as real), stops
	 * collectors spawning and resets the miss counter.
	 */
	public static void openPlan(float remaining) {
		if (remaining < 1f) {
			settleAndClear();
			return;
		}
		setPrincipal(remaining);
		put(KEY_PLAN, true);
		put(KEY_MISSED, 0);
		put(KEY_BOOKED, 0f);
		put(KEY_ENFORCE_SINCE, 0L);
	}

	/**
	 * This month's bill: the holder's interest PLUS a flat fraction of
	 * principal, so the balance falls by exactly that fraction every month
	 * regardless of the rate and the plan always terminates. Floored so a
	 * small plan finishes instead of dripping forever, and capped at the full
	 * payoff so the last month is never an overcharge.
	 */
	public static float installment() {
		return installmentFor(principal());
	}

	/**
	 * The same bill, for a balance that is not yet the principal.
	 *
	 * <p>The dialog needs this before terms are signed: the PAY option at zero
	 * credits is labelled with what the monthly figure WOULD be on the quoted
	 * balance, which is not the current principal - and on an inflated
	 * unchallenged quote is deliberately not even close to it. Exposed so the
	 * formula lives in exactly one place; it was mirrored by hand once and a
	 * mirrored formula drifts.
	 *
	 * @param balance the balance to bill against
	 * @return credits per month, or 0 for a balance too small to bill
	 */
	public static float installmentFor(float balance) {
		if (balance < 1f) return 0f;
		float rate = monthlyRate();
		float raw = balance * PiratePatConfig.debtInstallmentFraction() + balance * rate;
		float min = PiratePatConfig.debtInstallmentMin();
		float max = balance + balance * rate;
		if (raw < min) raw = min;
		if (raw > max) raw = max;
		return raw;
	}

	/**
	 * Record what the monthly report booked against the plan.
	 *
	 * <p>Critique F4: book the FULL installment, never min(installment, cash).
	 * If only what the player can afford is booked, the line always reads as
	 * paid, booked never differs from the installment, the miss counter can
	 * never increment, and the forced-sale-on-default road is dead code.
	 */
	public static void setBooked(float amount) {
		put(KEY_BOOKED, amount < 0f ? 0f : amount);
	}

	/**
	 * A payment came up short. Adds a late fee on the SHORTFALL (not the
	 * balance), opens the enforcement window at the configured miss count,
	 * and forces the sale at the second threshold - the road to the pirate
	 * act for a player who signs terms and never fires a shot.
	 *
	 * <p>THE PLAN IS TORN UP AT THE SALE, and only there. canDispatchCollector()
	 * refuses to send anyone at a player who has signed terms, so leaving
	 * KEY_PLAN set through the forced sale would sell the paper to the pirates
	 * and then never let a pirate collector near the player - the second act
	 * would be unreachable for exactly the non-combat player it was written
	 * for. It cannot be torn up earlier, at the enforcement threshold: the
	 * ticker only books an installment while hasPlan(), so cancelling at two
	 * misses would freeze the miss counter and make the third miss - and the
	 * sale itself - unreachable instead. The enforcement window opened one
	 * miss ago and is re-stamped by the branch above on this very call, so
	 * collectors resume at the doubled rate the moment the pirates take over.
	 *
	 * @param shortfall credits of the installment that were not covered
	 * @return true if this miss forced the sale
	 */
	public static boolean recordMiss(float shortfall) {
		int count = missed() + 1;
		put(KEY_MISSED, count);
		put(KEY_BOOKED, 0f);

		if (shortfall > 0f) {
			float fee = shortfall * PiratePatConfig.debtLateFeeFraction();
			if (fee > 0f) {
				addPrincipal(fee);
				PiratePatData.addLedger("Missed a payment - half the shortfall is added back", fee);
			}
		}

		if (count >= PiratePatConfig.debtMissesBeforeEnforcement()) {
			put(KEY_ENFORCE_SINCE, clock().getTimestamp());
		}
		if (count >= PiratePatConfig.debtMissesBeforeSale()) {
			put(KEY_MISSED, 0);
			// the terms are void - see the javadoc. Done whether or not the
			// sale itself fires, because a debt already in pirate hands is not
			// going to keep honouring a schedule the debtor stopped keeping.
			put(KEY_PLAN, false);
			put(KEY_BOOKED, 0f);
			return sellToPirates("payments stopped arriving");
		}
		return false;
	}

	/** A full payment landed: the consecutive-miss counter resets. */
	public static void recordSuccess() {
		put(KEY_MISSED, 0);
		put(KEY_BOOKED, 0f);
	}

	// ------------------------------------------------------------------
	// collector sizing - the anti-doomstack formula
	// ------------------------------------------------------------------

	/**
	 * The player's COMBAT fleet points. getFleetPoints() is deliberately not
	 * used: it returns an int and counts civilian hulls, so a fat logistics
	 * train would summon a war fleet.
	 */
	public static int playerCombatFP() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return 0;
		int total = 0;
		for (FleetMemberAPI member : player.getFleetData().getMembersListCopy()) {
			if (member == null) continue;
			if (member.isMothballed() || member.isFighterWing() || member.isCivilian()) continue;
			total += member.getFleetPointCost();
		}
		return total;
	}

	/**
	 * Largest player combat hull as a FleetParamsV3.maxShipSize value:
	 * 1 frigate, 2 destroyer, 3 cruiser, 4 capital. 0 when the player has no
	 * combat ships at all.
	 */
	public static int largestPlayerCombatHullSize() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return 0;
		int largest = 0;
		for (FleetMemberAPI member : player.getFleetData().getMembersListCopy()) {
			if (member == null) continue;
			if (member.isMothballed() || member.isFighterWing() || member.isCivilian()) continue;
			if (member.getHullSpec() == null) continue;
			HullSize size = member.getHullSpec().getHullSize();
			int val = 0;
			if (size == HullSize.FRIGATE) val = 1;
			else if (size == HullSize.DESTROYER) val = 2;
			else if (size == HullSize.CRUISER) val = 3;
			else if (size == HullSize.CAPITAL_SHIP) val = 4;
			if (val > largest) largest = val;
		}
		return largest;
	}

	/**
	 * The single most important anti-doomstack guard for game feel: a frigate
	 * player never sees a cruiser, full stop. One size above the player's
	 * biggest hull, capped at capital, floored at frigate.
	 */
	public static int collectorMaxShipSize() {
		int largest = largestPlayerCombatHullSize();
		int size = largest + 1;
		if (size > 4) size = 4;
		if (size < 1) size = 1;
		return size;
	}

	/**
	 * Fleet points for THE collector. There is exactly one, and it is sent at
	 * full strength from the first day: no ladder of smaller fleets, no
	 * escalation index, no top-up fleets. The player's COMBAT fleet points
	 * times debtCollectorFPRatio - which at the default 1.2 is what the old
	 * design only reached at its escalation cap.
	 *
	 * <p>The floor is clamped to the player (critique F1) so a two-frigate
	 * captain is never sent something bigger than they are, and the cap is
	 * the hard ceiling for one fleet. There is deliberately NO term in
	 * credits: the point of one full-strength fleet is that it is never a
	 * baby fleet, and a balance too small to be worth this is filtered by
	 * debtCollectorMinDebt before anyone is dispatched at all.
	 *
	 * <p>Residual the manager must know about: FleetFactoryV3:268 rewrites any
	 * non-zero combatPts below 10 as 5 + random(6), so a request of 6 comes
	 * back as 5-10 whatever we ask for. collectorMaxShipSize() is what keeps
	 * that honest at the bottom of the curve.
	 *
	 * @return fleet points, or 0 meaning "do not spawn"
	 */
	public static float collectorFP() {
		int playerFP = playerCombatFP();
		if (playerFP <= 0) return 0f;
		if (!hasDebt()) return 0f;

		float fp = playerFP * PiratePatConfig.debtCollectorFPRatio();

		float floor = Math.min(PiratePatConfig.debtCollectorMinFP(), playerFP);
		if (fp < floor) fp = floor;

		float cap = PiratePatConfig.debtCollectorMaxFP();
		if (fp > cap) fp = cap;
		return fp < 0f ? 0f : fp;
	}

	// ------------------------------------------------------------------
	// spawn gating
	// ------------------------------------------------------------------

	/**
	 * Whether a collector may be dispatched right now, ignoring the spawn
	 * roll and the one-fleet rule (both of which are the manager's).
	 * Collectors never chase a player who has signed terms - that is the
	 * whole relief a plan buys.
	 */
	public static boolean canDispatchCollector() {
		if (!isLive()) return false;
		if (hasPlan()) return false;
		if (principal() < PiratePatConfig.debtCollectorMinDebt()) return false;
		return daysOpen() >= PiratePatConfig.debtGraceDays();
	}

	/** Whether the day-N written notice is due. */
	public static boolean isNoticeDue() {
		if (!isLive()) return false;
		if (isNoticeSent()) return false;
		if (principal() < PiratePatConfig.debtCollectorMinDebt()) return false;
		return daysOpen() >= PiratePatConfig.debtNoticeDays();
	}

	// ------------------------------------------------------------------
	// display helpers
	// ------------------------------------------------------------------

	/** Colour for the true balance wherever it is printed beside a quote. */
	public static Color trueBalanceColor() {
		return Misc.getHighlightColor();
	}

	/**
	 * "the Hegemony", or "a faction nobody talks about any more" when the
	 * origin faction has been uninstalled. Every faction lookup in this class
	 * tolerates a null from getFaction().
	 */
	public static String originName() {
		FactionAPI faction = originFaction();
		if (faction == null) return "interests nobody names any more";
		return faction.getDisplayNameWithArticle();
	}

	/** "a collection house in independent space" / "the pirates". */
	public static String holderName() {
		if (isPirateHeld()) return "the pirates";
		return "a collection house in independent space";
	}
}
