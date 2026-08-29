package piratepat;

import java.util.Map;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.missions.CustomProductionContract;

/**
 * Vanilla's custom production contract, with the war chest listening: when
 * the mission giver is an underworld figure, the money the player hands
 * over is underworld revenue like any other - it feeds the chest and counts
 * as personal contribution in full. Registered by overriding the cpc row in
 * person_missions.csv; trade and military givers credit nothing.
 */
public class PatronageCustomProductionContract extends CustomProductionContract {

	public static boolean isUnderworldGiver(PersonAPI person) {
		if (person == null) return false;
		if (person.hasTag(Tags.CONTACT_UNDERWORLD)) return true;
		return person.getFaction() != null
				&& Factions.PIRATES.equals(person.getFaction().getId());
	}

	@Override
	public void acceptImpl(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
		super.acceptImpl(dialog, memoryMap);
		if (!PiratePatConfig.enabled()) return;
		if (cost <= 0) return;
		if (!isUnderworldGiver(getPerson())) return;
		PiratePatData.addUnderworldSpend(cost,
				"Custom production through " + getPerson().getNameString());
	}
}
