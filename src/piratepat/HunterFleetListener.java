package piratepat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
import com.fs.starfarer.api.util.Misc;

/**
 * Attached to each bounty hunter fleet. When the player destroys the fleet in
 * battle, the sponsoring faction's bounty goes UP - killing the hunters they
 * sent makes you more notorious and more wanted, not less. Serialized with
 * the fleet, so it stores only the faction id.
 */
public class HunterFleetListener implements FleetEventListener {

	protected String factionId;

	public HunterFleetListener(String factionId) {
		this.factionId = factionId;
	}

	public void reportFleetDespawnedToListener(CampaignFleetAPI fleet,
			FleetDespawnReason reason, Object param) {
		if (reason != FleetDespawnReason.DESTROYED_BY_BATTLE) return;
		if (!(param instanceof BattleAPI)) return;
		BattleAPI battle = (BattleAPI) param;
		if (!battle.isPlayerInvolved()) return;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.bountyEnabled()) return;

		float before = PiratePatData.getBounty(factionId);
		float increase = PiratePatConfig.bountyPerKillFlat()
				+ before * PiratePatConfig.bountyPerKillFraction();
		if (increase <= 0) return;

		PiratePatData.raiseBounty(factionId, increase);

		FactionAPI faction = Global.getSector().getFaction(factionId);
		String name = faction != null ? Misc.ucFirst(faction.getDisplayName()) : factionId;
		PiratePatData.addLedger("Destroyed " + name + " hunters - the price on your head rises",
				increase);

		MessageIntel msg = new MessageIntel();
		msg.addLine("Bounty increased", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Defeating their hunters has made you more notorious.",
				Misc.getTextColor());
		msg.addLine(BaseIntelPlugin.BULLET + "%s bounty: %s", Misc.getTextColor(),
				new String[] { name, Misc.getDGSCredits(PiratePatData.getBounty(factionId)) },
				Misc.getHighlightColor());
		if (faction != null) msg.setIcon(faction.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg, MessageClickAction.INTEL_TAB);
	}

	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
			BattleAPI battle) {
	}
}
