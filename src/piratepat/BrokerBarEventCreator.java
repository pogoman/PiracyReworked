package piratepat;

import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventCreator;

public class BrokerBarEventCreator extends BaseBarEventCreator {

	public PortsideBarEvent createBarEvent() {
		return new BrokerBarEvent();
	}

	@Override
	public float getBarEventFrequencyWeight() {
		// the network is not hard to find if you know which bars to drink in
		return super.getBarEventFrequencyWeight() * 2f;
	}

	@Override
	public float getBarEventTimeoutDuration() {
		// after placing an order, the fixer has nothing new for a while
		return 30f;
	}

	@Override
	public float getBarEventActiveDuration() {
		return 30f + (float) Math.random() * 30f;
	}
}
