package piratepat;

import java.util.Map;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.missions.HandMeDownFreighter;

/**
 * Vanilla's hand-me-down freighter sale, with the war chest listening: a
 * beat-up freighter bought off an underworld figure is underworld revenue.
 * Registered by overriding the hmdf row in person_missions.csv; trade
 * givers credit nothing.
 */
public class PatronageHandMeDownFreighter extends HandMeDownFreighter {

	@Override
	public void accept(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
		super.accept(dialog, memoryMap);
		if (!PiratePatConfig.enabled()) return;
		if (price <= 0) return;
		if (!PatronageCustomProductionContract.isUnderworldGiver(getPerson())) return;
		PiratePatData.addUnderworldSpend(price,
				"A ship of convenient provenance from " + getPerson().getNameString());
	}
}
