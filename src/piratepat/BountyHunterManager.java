package piratepat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.log4j.Logger;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission;
import com.fs.starfarer.api.impl.campaign.missions.hub.MissionFleetAutoDespawn;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * Sends independent bounty hunter fleets after the player. The ledgers are
 * per faction - each poster can be paid off separately - but the hunters
 * are freelancers who collect from every poster at once, so everything on
 * their side of the table works off the POOLED total: whether the price is
 * worth taking against the player's fleet, how strong a fleet it funds
 * (capped per fleet), and how many fleets hunt concurrently. Hunters are
 * no-rep-impact - this is private violence, not faction war.
 */
public class BountyHunterManager implements EveryFrameScript {

	public static Logger log = Global.getLogger(BountyHunterManager.class);

	public static final String HUNTER_FLAG = "$piratepat_hunter";
	public static final float HUNT_DAYS = 60f;

	protected IntervalUtil checkInterval = new IntervalUtil(7.5f, 12.5f);
	protected IntervalUtil monthly = new IntervalUtil(25f, 35f);
	protected Random random = new Random();

	protected Map<String, List<CampaignFleetAPI>> hunters = new LinkedHashMap<String, List<CampaignFleetAPI>>();

	/** XStream skips field initializers on load - guard every field. */
	protected Object readResolve() {
		if (checkInterval == null) checkInterval = new IntervalUtil(7.5f, 12.5f);
		if (monthly == null) monthly = new IntervalUtil(25f, 35f);
		if (random == null) random = new Random();
		if (hunters == null) hunters = new LinkedHashMap<String, List<CampaignFleetAPI>>();
		return this;
	}

	public void advance(float amount) {
		if (!PiratePatConfig.enabled() || !PiratePatConfig.bountyEnabled()) return;
		float days = Global.getSector().getClock().convertToDays(amount);

		monthly.advance(days);
		if (monthly.intervalElapsed()) {
			growBounties();
		}

		checkInterval.advance(days);
		if (!checkInterval.intervalElapsed()) return;

		cleanup();

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || player.getContainingLocation() == null) return;

		float strength = player.getEffectiveStrength();
		float fleetValue = playerFleetValue();

		// hunters see ONE price: the combined total across every poster. The
		// pool is "active" once it clears the activation floor, funds hunters
		// worth the player's CURRENT strength, AND the fleet it targets is
		// worth enough to bother with (a decoy fleet freezes it). That
		// dormant -> active transition is the ONE moment we notify, and it is
		// what puts the intel on the list.
		float total = PiratePatData.getTotalBounty();
		boolean nowActive = isActiveBounty(total, strength, fleetValue);
		boolean wasActive = PiratePatData.isPoolActive();
		if (nowActive && !wasActive) {
			PiratePatData.setPoolActive(true);
			notifyBountyActivated(total);
		} else if (!nowActive && wasActive) {
			PiratePatData.setPoolActive(false);
		}

		if (!nowActive) return;

		int maxFleets = 1 + (int) (total / PiratePatConfig.bountyCreditsPerExtraFleet());
		if (maxFleets > PiratePatConfig.bountyMaxFleetsPerFaction()) {
			maxFleets = PiratePatConfig.bountyMaxFleetsPerFaction();
		}
		if (getTotalActiveHunters() >= maxFleets) return;

		if (random.nextFloat() > PiratePatConfig.bountySpawnProb()) return;

		spawnHunterFleet(total, player);
	}

	/**
	 * A bounty draws hunters only when it clears the activation floor, funds a
	 * hunter that credibly threatens the player's current fleet (the worth-it
	 * deterrence), AND is not frozen against a decoy fleet. Fail any bar and it
	 * is dormant/frozen: no hunters, intel hidden.
	 */
	public static boolean isActiveBounty(float bounty, float playerStrength, float fleetValue) {
		if (bounty < PiratePatConfig.bountyActivationMin()) return false;
		if (isFrozenByFleetValue(bounty, fleetValue)) return false;
		float worthItFrac = PiratePatConfig.bountyWorthItFraction();
		if (worthItFrac > 0f && fundedHunterStrength(bounty) < playerStrength * worthItFrac) {
			return false;
		}
		return true;
	}

	/**
	 * Total hunter strength a bounty can MOTIVATE, in fleet points and UNCAPPED -
	 * used only to judge whether the contract is worth taking against the
	 * player's current fleet. The individual fleets actually dispatched are still
	 * capped by hunterFPForBounty; this is the reward-vs-risk figure, and because
	 * it grows without bound as the bounty festers upward, no fleet is ever
	 * permanently exempt - a big enough price always overcomes the deterrence.
	 * A deployment-cost-reduced whale fleet just carries a bigger price before
	 * hunters commit.
	 */
	public static float fundedHunterStrength(float bounty) {
		float perFleet = bounty / PiratePatConfig.bountyCreditsPerFP();
		int maxFleets = 1 + (int) (bounty / PiratePatConfig.bountyCreditsPerExtraFleet());
		if (maxFleets > PiratePatConfig.bountyMaxFleetsPerFaction()) {
			maxFleets = PiratePatConfig.bountyMaxFleetsPerFaction();
		}
		return perFleet * maxFleets;
	}

	/**
	 * The contract wants the player's FLEET destroyed, so it is worthless against
	 * a throwaway fleet. If the player's ship value is far below the posted
	 * bounty the contract is frozen (hidden, no growth, no hunters) rather than
	 * letting them clear a big bounty by dying on purpose in a cheap fleet.
	 */
	public static boolean isFrozenByFleetValue(float bounty, float fleetValue) {
		float frac = PiratePatConfig.bountyMinFleetValueFraction();
		if (frac <= 0f) return false;
		return fleetValue < bounty * frac;
	}

	protected static float playerEffectiveStrength() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		return player == null ? 0f : player.getEffectiveStrength();
	}

	/** Total credit value of the player's ships - what the contract stands to destroy. */
	protected static float playerFleetValue() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null) return 0f;
		float total = 0f;
		for (FleetMemberAPI m : player.getFleetData().getMembersListCopy()) {
			total += m.getBaseValue();
		}
		return total;
	}

	/**
	 * Monthly compounding, at a rate set by the POOL's state: while the
	 * combined price is active every ledger accrues interest at the normal
	 * rate, while it's too small (or too weak against the player's fleet)
	 * every ledger festers at the higher dormant rate, and while it's frozen
	 * against a decoy fleet nothing grows.
	 */
	protected void growBounties() {
		float strength = playerEffectiveStrength();
		float fleetValue = playerFleetValue();
		float total = PiratePatData.getTotalBounty();
		float rate;
		if (isActiveBounty(total, strength, fleetValue)) rate = PiratePatConfig.bountyGrowthPerMonth();
		else if (isFrozenByFleetValue(total, fleetValue)) rate = 0f;
		else rate = PiratePatConfig.bountyDormantGrowthPerMonth();
		if (rate == 0f) return;

		Map<String, Float> bounties = PiratePatData.bounties();
		List<String> remove = new ArrayList<String>();
		for (Map.Entry<String, Float> entry : bounties.entrySet()) {
			float val = entry.getValue() * (1f + rate);
			if (val < 1000f) remove.add(entry.getKey());
			else entry.setValue(val);
		}
		for (String k : remove) {
			bounties.remove(k);
			PiratePatData.activeBountyFactions().remove(k);
		}
	}

	/** Fired once, when the combined price first becomes worth hunting. */
	protected void notifyBountyActivated(float total) {
		int posters = PiratePatData.bounties().size();
		PiratePatData.addLedger("The combined price on your head draws hunters' notice", 0f);

		MessageIntel msg = new MessageIntel();
		msg.addLine("A price has been placed on your head", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Combined bounty: %s, posted by %s "
				+ (posters == 1 ? "faction" : "factions"), Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(total), "" + posters }, Misc.getHighlightColor());
		FactionAPI indep = Global.getSector().getFaction(Factions.INDEPENDENT);
		if (indep != null) msg.setIcon(indep.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg, MessageClickAction.INTEL_TAB);
	}

	protected List<CampaignFleetAPI> getHunters(String factionId) {
		List<CampaignFleetAPI> list = hunters.get(factionId);
		if (list == null) {
			list = new ArrayList<CampaignFleetAPI>();
			hunters.put(factionId, list);
		}
		return list;
	}

	protected void cleanup() {
		for (List<CampaignFleetAPI> list : hunters.values()) {
			Iterator<CampaignFleetAPI> iter = list.iterator();
			while (iter.hasNext()) {
				CampaignFleetAPI curr = iter.next();
				if (curr == null || !curr.isAlive() || curr.getContainingLocation() == null) {
					iter.remove();
				}
			}
		}
	}

	/** Fleet points the bounty can fund, capped at the per-fleet maximum. */
	public static float hunterFPForBounty(float bounty) {
		float fp = bounty / PiratePatConfig.bountyCreditsPerFP();
		if (fp > PiratePatConfig.bountyMaxFPPerFleet()) fp = PiratePatConfig.bountyMaxFPPerFleet();
		return fp;
	}

	protected void spawnHunterFleet(float bounty, CampaignFleetAPI player) {
		float fp = hunterFPForBounty(bounty);

		// FleetCreatorMission difficulty units are roughly 15-20 FP each for
		// a quality (mercenary) fleet
		int difficulty = Math.round(fp / 18f);
		if (difficulty < 2) difficulty = 2;
		if (difficulty > 12) difficulty = 12;

		LocationAPI where = player.getContainingLocation();

		FleetCreatorMission m = new FleetCreatorMission(random);
		m.beginFleet();
		Vector2f locInHyper = player.getLocationInHyperspace();
		m.createQualityFleet(difficulty, Factions.MERCENARY, locInHyper);
		m.triggerSetFleetFaction(Factions.INDEPENDENT);
		m.triggerMakeNoRepImpact();
		m.triggerMakeHostileAndAggressive();
		m.triggerFleetAllowLongPursuit();
		m.triggerFleetSetAllWeapons();

		CampaignFleetAPI fleet = m.createFleet();
		if (fleet == null) return;

		fleet.setName("Bounty Hunters");
		fleet.getMemoryWithoutUpdate().set(HUNTER_FLAG, true);
		fleet.addEventListener(new HunterFleetListener(null)); // null = collects from the pool
		Misc.setFlagWithReason(fleet.getMemoryWithoutUpdate(),
				MemFlags.MEMORY_KEY_PURSUE_PLAYER, "piratepat_bounty", true, HUNT_DAYS);

		Vector2f spawnLoc = Misc.getPointWithinRadius(player.getLocation(), 4000f + random.nextFloat() * 3000f);
		where.addEntity(fleet);
		fleet.setLocation(spawnLoc.x, spawnLoc.y);

		fleet.addAssignment(FleetAssignment.INTERCEPT, player, HUNT_DAYS, "hunting a fugitive");
		fleet.removeScriptsOfClass(MissionFleetAutoDespawn.class);
		fleet.addScript(new MissionFleetAutoDespawn(null, fleet));

		getHunters("pool").add(fleet);

		if (PiratePatConfig.debugLogging()) {
			log.info("Spawned bounty hunter fleet (difficulty " + difficulty + ", ~" + (int) fp
					+ " FP) for combined bounty of " + (int) bounty);
		}
	}

	public int getTotalActiveHunters() {
		int total = 0;
		for (List<CampaignFleetAPI> list : hunters.values()) total += list.size();
		return total;
	}

	public boolean isDone() {
		return false;
	}

	public boolean runWhilePaused() {
		return false;
	}
}
