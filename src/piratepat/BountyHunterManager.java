package piratepat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * DEPRECATED - a frozen deserialization stub, kept only so existing saves can
 * still be loaded. Bounty hunters were replaced in v0.2.0 by
 * DebtCollectorManager, which sizes collectors off the player's own combat
 * fleet points instead of off a number in credits.
 *
 * <p>THIS CLASS MUST NEVER BE DELETED AND MUST NEVER LOSE A FIELD. 0.98a-RC8
 * does not enable XStream's global ignoreUnknownElements, so an unknown class
 * name OR an unknown element inside a known class is a hard load failure. An
 * instance of this class sits in sector.getScripts() in every save made by
 * v0.1.x, and each of the four fields below is written into that save as a
 * child element. Deleting the class, or dropping any one of checkInterval,
 * monthly, random or hunters, corrupts those saves permanently.
 *
 * <p>All four fields and readResolve() are therefore preserved VERBATIM from
 * the working implementation. Only the method bodies are emptied - a kept
 * class has to be gutted rather than merely kept, because the old bodies
 * called PiratePatData bounty accessors and PiratePatConfig bounty settings
 * that no longer exist.
 *
 * <p>PiratePatModPlugin.onGameLoad removes the instance from the script list on
 * every load, so the stub never actually runs; isDone() returning true is the
 * second line of defence in case the removal is ever skipped.
 */
public class BountyHunterManager implements EveryFrameScript {

	public static Logger log = Global.getLogger(BountyHunterManager.class);

	/**
	 * Fleet-memory flag on every legacy hunter fleet. Retained because it is
	 * the ONLY handle the load-time migration has for finding those fleets and
	 * standing them down.
	 */
	public static final String HUNTER_FLAG = "$piratepat_hunter";

	/** Length of the old INTERCEPT assignment. Retained for reference only. */
	public static final float HUNT_DAYS = 60f;

	protected IntervalUtil checkInterval = new IntervalUtil(7.5f, 12.5f);
	protected IntervalUtil monthly = new IntervalUtil(25f, 35f);
	protected Random random = new Random();

	protected Map<String, List<CampaignFleetAPI>> hunters = new LinkedHashMap<String, List<CampaignFleetAPI>>();

	/** XStream skips field initializers on load - guard every field. */
	protected Object readResolve() {
		if (checkInterval == null) checkInterval = new IntervalUtil(7.5f, 12.5f);
		if (monthly == null) monthly = new IntervalUtil(25f, 35f);
		if (random == null) random = new Random();
		if (hunters == null) hunters = new LinkedHashMap<String, List<CampaignFleetAPI>>();
		return this;
	}

	public void advance(float amount) {
	}

	public boolean isDone() {
		return true;
	}

	public boolean runWhilePaused() {
		return false;
	}
}
