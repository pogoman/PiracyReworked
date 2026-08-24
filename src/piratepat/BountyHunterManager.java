package piratepat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.missions.FleetCreatorMission;
import com.fs.starfarer.api.impl.campaign.missions.hub.MissionFleetAutoDespawn;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;

/**
 * Sends independent bounty hunter fleets after the player, per faction with
 * an active personal bounty. Fleet strength scales with the bounty (capped),
 * and past the cap the bounty funds additional concurrent fleets. Hunters
 * are no-rep-impact - this is private violence, not faction war.
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
			PiratePatData.decayBounties();
		}

		checkInterval.advance(days);
		if (!checkInterval.intervalElapsed()) return;

		cleanup();

		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || player.getContainingLocation() == null) return;

		for (Map.Entry<String, Float> entry : new LinkedHashMap<String, Float>(PiratePatData.bounties()).entrySet()) {
			String factionId = entry.getKey();
			float bounty = entry.getValue();
			if (bounty < PiratePatConfig.bountyActivationMin()) continue;

			int maxFleets = 1 + (int) (bounty / PiratePatConfig.bountyCreditsPerExtraFleet());
			if (maxFleets > PiratePatConfig.bountyMaxFleetsPerFaction()) {
				maxFleets = PiratePatConfig.bountyMaxFleetsPerFaction();
			}
			if (getHunters(factionId).size() >= maxFleets) continue;

			if (random.nextFloat() > PiratePatConfig.bountySpawnProb()) continue;

			spawnHunterFleet(factionId, bounty, player);
		}
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

	protected void spawnHunterFleet(String factionId, float bounty, CampaignFleetAPI player) {
		float fp = bounty / PiratePatConfig.bountyCreditsPerFP();
		if (fp > PiratePatConfig.bountyMaxFPPerFleet()) fp = PiratePatConfig.bountyMaxFPPerFleet();

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
		fleet.getMemoryWithoutUpdate().set(HUNTER_FLAG + "_faction", factionId);
		fleet.addEventListener(new HunterFleetListener(factionId));
		Misc.setFlagWithReason(fleet.getMemoryWithoutUpdate(),
				MemFlags.MEMORY_KEY_PURSUE_PLAYER, "piratepat_bounty", true, HUNT_DAYS);

		Vector2f spawnLoc = Misc.getPointWithinRadius(player.getLocation(), 4000f + random.nextFloat() * 3000f);
		where.addEntity(fleet);
		fleet.setLocation(spawnLoc.x, spawnLoc.y);

		fleet.addAssignment(FleetAssignment.INTERCEPT, player, HUNT_DAYS, "hunting a fugitive");
		fleet.removeScriptsOfClass(MissionFleetAutoDespawn.class);
		fleet.addScript(new MissionFleetAutoDespawn(null, fleet));

		getHunters(factionId).add(fleet);

		if (PiratePatConfig.debugLogging()) {
			log.info("Spawned bounty hunter fleet (difficulty " + difficulty + ", ~" + (int) fp
					+ " FP) for " + factionId + " bounty of " + (int) bounty);
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
