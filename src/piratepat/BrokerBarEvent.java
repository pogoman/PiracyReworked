package piratepat;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventWithPerson;

/**
 * DEPRECATED - the broker moved from the portside bar to underworld contacts
 * (see BrokerDialog / PiratepatBrokerCMD). This stub is kept only so saves
 * that serialized an instance still load; it hides itself and asks to be
 * removed, and the mod plugin also purges leftovers on load.
 */
public class BrokerBarEvent extends BaseBarEventWithPerson {

	@Override
	public boolean shouldShowAtMarket(MarketAPI market) {
		return false;
	}

	@Override
	public boolean shouldRemoveEvent() {
		return true;
	}
}
