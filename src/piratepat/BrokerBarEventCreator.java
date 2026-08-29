package piratepat;

import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventCreator;

/**
 * DEPRECATED - the broker moved from the portside bar to underworld contacts
 * (see BrokerDialog / PiratepatBrokerCMD). This stub is kept only so saves
 * that serialized the creator still load; it never fires, and the mod plugin
 * removes it from the bar event manager on load.
 */
public class BrokerBarEventCreator extends BaseBarEventCreator {

	public PortsideBarEvent createBarEvent() {
		return new BrokerBarEvent();
	}

	@Override
	public float getBarEventFrequencyWeight() {
		return 0f;
	}
}
