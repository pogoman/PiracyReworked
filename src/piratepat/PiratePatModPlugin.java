package piratepat;

import java.util.ArrayList;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData;
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseManager;

public class PiratePatModPlugin extends BaseModPlugin {

	public static Logger log = Global.getLogger(PiratePatModPlugin.class);

	@Override
	public void onGameLoad(boolean newGame) {
		SectorAPI sector = Global.getSector();

		// Replace the vanilla base manager with the war chest economy. Exact
		// class match only - our subclass (from a save) must survive this.
		float carryDays = -1f;
		for (EveryFrameScript s : new ArrayList<EveryFrameScript>(sector.getScripts())) {
			if (s.getClass() == PirateBaseManager.class) {
				carryDays = ((PirateBaseManager) s).getUnadjustedDaysSinceStart();
				sector.removeScript(s);
				log.info("Removed vanilla PirateBaseManager (elapsed days carried over: "
						+ (int) carryDays + ")");
			}
		}

		PatronageBaseManager mgr = null;
		for (EveryFrameScript s : sector.getScripts()) {
			if (s instanceof PatronageBaseManager) {
				mgr = (PatronageBaseManager) s;
				break;
			}
		}
		if (mgr == null) {
			mgr = new PatronageBaseManager();
			if (carryDays > 0) mgr.setExtraDays(carryDays);
			sector.addScript(mgr);
			log.info("Installed PatronageBaseManager");
		}

		// vanilla code paths resolve the manager through this memory key
		sector.getMemoryWithoutUpdate().set(PirateBaseManager.KEY, mgr);

		mgr.adoptExistingBases();
		PiratePatData.seedIfNeeded(mgr.getBases().size());

		BountyHunterManager bountyMgr = null;
		for (EveryFrameScript s : sector.getScripts()) {
			if (s instanceof BountyHunterManager) {
				bountyMgr = (BountyHunterManager) s;
				break;
			}
		}
		if (bountyMgr == null) {
			sector.addScript(new BountyHunterManager());
			log.info("Installed BountyHunterManager");
		}

		// saves from before the machine-faction exclusion may carry bounties
		// from factions that don't post them (the Threat hive...)
		PiratePatData.purgeInvalidBounties();

		// transient: re-added every load, never serialized into the save
		sector.getListenerManager().addListener(new BlackMarketListener(), true);
		sector.getListenerManager().addListener(new PirateHuntListener(), true);

		// the broker lives on underworld contacts now (rules.csv +
		// PiratepatBrokerCMD). Purge the old bar event machinery from saves
		// that serialized it.
		BarEventManager bars = BarEventManager.getInstance();
		if (bars != null && bars.hasEventCreator(BrokerBarEventCreator.class)) {
			for (BarEventManager.GenericBarEventCreator c
					: new ArrayList<BarEventManager.GenericBarEventCreator>(bars.getCreators())) {
				if (c instanceof BrokerBarEventCreator) bars.getCreators().remove(c);
			}
			log.info("Removed deprecated BrokerBarEventCreator");
		}
		for (PortsideBarEvent e : new ArrayList<PortsideBarEvent>(
				PortsideBarData.getInstance().getEvents())) {
			if (e instanceof BrokerBarEvent) PortsideBarData.getInstance().removeEvent(e);
		}

		WarChestIntel.ensureAdded();
	}
}
