package piratepat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.DebugFlags;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateActivityIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.PiracyRespiteScript;

/**
 * Pirate activity from a war-chest-funded base. Identical to vanilla except
 * for one thing: Piracy Respite's fiction is that pirates are WARY of
 * attacking you - and wariness fades when the war chest overflows with your
 * own money. Past the configured thresholds (lifetime contribution and share
 * of underworld income), the respite exemption for player-owned markets no
 * longer applies: your colonies get the activity penalties and ambient fleets
 * like anyone else's. Never raids - only the vanilla ambient pressure.
 *
 * The player's share dilutes over time as the pirate economy earns its own
 * money, so laying off the black market eventually restores the respite.
 */
public class PatronageActivityIntel extends PirateActivityIntel {

	protected boolean announcedPiercing = false;

	public PatronageActivityIntel(StarSystemAPI system, PatronageBaseIntel source) {
		super(system, source);
	}

	public static boolean isPiercingActive() {
		if (!PiratePatConfig.enabled()) return false;
		if (!PiratePatConfig.respitePiercing()) return false;
		return PiratePatData.getLifetimePlayerContribution() >= PiratePatConfig.pierceMinContribution()
				&& PiratePatData.getPlayerShare() >= PiratePatConfig.pierceMinShare();
	}

	@Override
	protected void advanceImpl(float amount) {
		if (source.isEnding() || source.getTarget() != system) {
			endAfterDelay();
			if (DebugFlags.SEND_UPDATES_WHEN_NO_COMM ||
					Global.getSector().getIntelManager().isPlayerInRangeOfCommRelay()) {
				sendUpdateIfPlayerHasIntel(new Object(), false);
			}
			return;
		}

		boolean pierce = isPiercingActive();

		for (MarketAPI curr : source.getAffectedMarkets(system)) {
			if (curr.isPlayerOwned() && PiracyRespiteScript.get() != null && !pierce) {
				// respite honored (or piercing lapsed): any lingering condition
				// on a player market under respite can only have come from us
				if (curr.hasCondition(Conditions.PIRATE_ACTIVITY)) {
					curr.removeCondition(Conditions.PIRATE_ACTIVITY);
				}
				continue;
			}

			if (!curr.hasCondition(Conditions.PIRATE_ACTIVITY)) {
				curr.addCondition(Conditions.PIRATE_ACTIVITY, source);
				if (curr.isPlayerOwned() && pierce && PiracyRespiteScript.get() != null
						&& !announcedPiercing) {
					announcedPiercing = true;
					PiratePatData.addLedger("Emboldened pirates begin preying on your colonies in the "
							+ system.getNameWithLowercaseTypeShort(), 0f);
				}
			}
		}
	}
}
