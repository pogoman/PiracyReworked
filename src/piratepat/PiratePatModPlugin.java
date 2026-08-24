package piratepat;

import java.util.ArrayList;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
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

		// transient: re-added every load, never serialized into the save
		sector.getListenerManager().addListener(new BlackMarketListener(), true);
		sector.getListenerManager().addListener(new PirateHuntListener(), true);

		WarChestIntel.ensureAdded();
	}
}
