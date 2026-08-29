package piratepat;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.missions.hub.BaseMissionHub;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc.Token;

/**
 * Rules glue for the broker: any underworld-tagged contact offers to have
 * things "sourced" alongside their regular job list.
 *
 * PiratepatBrokerCMD isBrokerContact - condition: is the active person an
 * underworld contact with a mission hub (same gate as the job list itself)?
 * PiratepatBrokerCMD open - swap in the broker conversation.
 */
public class PiratepatBrokerCMD extends BaseCommandPlugin {

	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params,
			Map<String, MemoryAPI> memoryMap) {
		if (dialog == null) return false;
		String command = params.get(0).getString(memoryMap);
		if (command == null) return false;

		PersonAPI person = dialog.getInteractionTarget().getActivePerson();

		if (command.equals("isBrokerContact")) {
			if (!PiratePatConfig.enabled() || !PiratePatConfig.brokerEnabled()) return false;
			if (person == null) return false;
			if (!person.hasTag(Tags.CONTACT_UNDERWORLD)) return false;
			// same availability gate as the contact's job list
			if (BaseMissionHub.get(person) == null) return false;
			// and the same personal-rep gate vanilla missions use: this is a
			// trust service, not something offered to strangers
			if (person.getRelToPlayer() == null
					|| !person.getRelToPlayer().isAtWorst(PiratePatConfig.brokerMinRep())) {
				return false;
			}
			return true;
		}

		if (command.equals("open")) {
			if (person == null) return false;
			MarketAPI market = person.getMarket();
			if (market == null && dialog.getInteractionTarget() != null) {
				market = dialog.getInteractionTarget().getMarket();
			}
			if (market == null) return false;
			BrokerDialog.begin(dialog, memoryMap, person, market);
			return true;
		}

		return false;
	}
}
