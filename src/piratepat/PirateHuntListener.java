package piratepat;

import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

/**
 * Sector-wide listener: every pirate fleet the player destroys outright
 * offsets their contribution ledger a little (credits per fleet point, from
 * the fleet's pre-battle snapshot). Quiet accumulation - no per-kill ledger
 * spam; the total shows in the war chest intel. Station fleets are excluded
 * (base destruction has its own, bigger offset).
 */
public class PirateHuntListener implements FleetEventListener {

	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
			FleetDespawnReason reason, Object param) {
		if (fleet == null) return;
		if (reason != FleetDespawnReason.DESTROYED_BY_BATTLE) return;
		if (!(param instanceof BattleAPI)) return;
		if (!((BattleAPI) param).isPlayerInvolved()) return;
		if (!PiratePatConfig.enabled()) return;
		if (fleet.getFaction() == null || !Factions.PIRATES.equals(fleet.getFaction().getId())) return;
		if (fleet.isStationMode()) return;

		float fp = 0f;
		for (FleetMemberAPI member : fleet.getFleetData().getSnapshot()) {
			fp += member.getFleetPointCost();
		}
		if (fp <= 0) return;

		PiratePatData.offsetFromPirateKills(fp * PiratePatConfig.offsetPerPirateFPDestroyed());
	}

	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
			BattleAPI battle) {
	}
}
