package piratepat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.CustomRepImpact;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActionEnvelope;
import com.fs.starfarer.api.impl.campaign.CoreReputationPlugin.RepActions;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel;
import com.fs.starfarer.api.campaign.comm.CommMessageAPI.MessageClickAction;
import com.fs.starfarer.api.util.Misc;

/**
 * Attached to each bounty hunter fleet. When the player destroys the fleet in
 * battle, the price on their head goes UP - killing hunters makes you more
 * notorious and more wanted, not less. Hunters collect from the POOLED
 * bounty, so the increase lands on every poster's ledger: the flat amount
 * split by each faction's share, plus the fractional interest on their own
 * figure. Serialized with the fleet; a non-null factionId is a fleet from
 * before pooling and raises only its sponsor's ledger.
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

		float totalIncrease = 0f;
		if (factionId != null) {
			// legacy pre-pooling fleet: raises only its sponsor's ledger
			float before = PiratePatData.getBounty(factionId);
			totalIncrease = PiratePatConfig.bountyPerKillFlat()
					+ before * PiratePatConfig.bountyPerKillFraction();
			if (totalIncrease <= 0) return;
			PiratePatData.raiseBounty(factionId, totalIncrease);
		} else {
			// pooled fleet: the flat amount lands on each poster by their
			// share of the pool, the fraction as interest on their own figure
			float total = PiratePatData.getTotalBounty();
			if (total <= 0) return;
			for (java.util.Map.Entry<String, Float> entry
					: new java.util.LinkedHashMap<String, Float>(PiratePatData.bounties()).entrySet()) {
				float share = entry.getValue() / total;
				float increase = PiratePatConfig.bountyPerKillFlat() * share
						+ entry.getValue() * PiratePatConfig.bountyPerKillFraction();
				if (increase <= 0) continue;
				PiratePatData.raiseBounty(entry.getKey(), increase);
				totalIncrease += increase;
			}
			if (totalIncrease <= 0) return;
		}

		PiratePatData.addLedger("Destroyed bounty hunters - the price on your head rises",
				totalIncrease);

		MessageIntel msg = new MessageIntel();
		msg.addLine("Bounty increased", Misc.getNegativeHighlightColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Defeating the hunters has made you more notorious.",
				Misc.getTextColor());
		msg.addLine(BaseIntelPlugin.BULLET + "Combined bounty: %s", Misc.getTextColor(),
				new String[] { Misc.getDGSCredits(PiratePatData.getTotalBounty()) },
				Misc.getHighlightColor());
		FactionAPI indep = Global.getSector().getFaction(Factions.INDEPENDENT);
		if (indep != null) msg.setIcon(indep.getCrest());
		Global.getSector().getCampaignUI().addMessage(msg, MessageClickAction.INTEL_TAB);

		// the underworld appreciates its patron handling the opposition
		int repPoints = PiratePatConfig.pirateRepPerHunterKill();
		if (repPoints > 0) {
			CustomRepImpact impact = new CustomRepImpact();
			impact.delta = repPoints * 0.01f;
			impact.limit = RepLevel.FRIENDLY;
			Global.getSector().adjustPlayerReputation(
					new RepActionEnvelope(RepActions.CUSTOM, impact, null, null, true, true,
							"Destroyed bounty hunters pursuing a patron of the underworld"),
					Factions.PIRATES);
		}
	}

	public void reportBattleOccurred(CampaignFleetAPI fleet, CampaignFleetAPI primaryWinner,
			BattleAPI battle) {
	}
}
