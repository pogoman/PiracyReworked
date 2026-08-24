package piratepat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;

/**
 * Sector-wide listener: pirate ships the player destroys in battle offset
 * their contribution ledger (credits per fleet point). Counts per-battle
 * losses via fleet snapshots, so partial kills count and routed survivors
 * don't - a fleet doesn't have to be wiped out for its dead to matter.
 * Station fleets are excluded (base destruction has its own, bigger offset).
 */
public class PirateHuntListener implements FleetEventListener {

	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
			FleetDespawnReason reason, Object param) {
	}

	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
			BattleAPI battle) {
		if (battle == null || !battle.isPlayerInvolved()) return;
		if (!PiratePatConfig.enabled()) return;

		float fp = 0f;
		for (CampaignFleetAPI other : battle.getNonPlayerSideSnapshot()) {
			if (other.getFaction() == null
					|| !Factions.PIRATES.equals(other.getFaction().getId())) continue;
			if (other.isStationMode()) continue;
			for (FleetMemberAPI member : Misc.getSnapshotMembersLost(other)) {
				fp += member.getFleetPointCost();
			}
		}
		if (fp <= 0) return;

		PiratePatData.offsetFromPirateKills(fp * PiratePatConfig.offsetPerPirateFPDestroyed(),
				(int) fp);
	}
}
