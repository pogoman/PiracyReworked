package piratepat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;

/**
 * DEPRECATED - a frozen deserialization stub, kept only so existing saves can
 * still be loaded. Its job (raising the price on the player's head when they
 * destroyed a hunter) no longer exists: under the debt system a destroyed
 * collector is the END of the debt - PirateDebt.reportCollectorEliminated.
 *
 * <p>THIS IS THE LEAST OBVIOUS SAVE HAZARD IN THE MOD. Nothing in the mod's
 * persistent data mentions this class, so a search of the save's top-level
 * state finds nothing - but an instance was attached to every hunter fleet
 * with addEventListener (old BountyHunterManager.java:274), which means it is
 * serialized INSIDE each live hunter fleet, and each of those nodes carries a
 * child element for factionId. 0.98a-RC8 does not enable XStream's global
 * ignoreUnknownElements, so deleting this class - or removing the factionId
 * field, or the one-argument constructor XStream never calls but which keeps
 * the field's declared shape honest - is a hard load failure for any save with
 * a hunter still in space.
 *
 * <p>protected String factionId and the one-arg constructor are therefore
 * preserved VERBATIM. Both listener methods are no-ops: the legacy fleets are
 * stood down and sent home by PiratePatModPlugin.onGameLoad on the first load
 * after the update, and a stub that still fired would raise a dead per-faction
 * bounty map and print "Combined bounty: 0 credits" at the player.
 */
public class HunterFleetListener implements FleetEventListener {

	protected String factionId;

	public HunterFleetListener(String factionId) {
		this.factionId = factionId;
	}

	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
			FleetDespawnReason reason, Object param) {
	}

	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
			BattleAPI battle) {
	}
}
