package piratepat;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;

/**
 * DEPRECATED - a frozen deserialization stub, kept only so existing saves can
 * still be loaded. The "A Price On Your Head" intel entry and its pay-off
 * buttons were removed in v0.2.0; the debt is settled fleet-to-fleet now, and
 * the balance is shown read-only on the War Chest intel.
 *
 * <p>THIS CLASS MUST NEVER BE DELETED. 0.98a-RC8 does not enable XStream's
 * global ignoreUnknownElements - disassembling CampaignGameManager shows a
 * single call, the String overload with the literal "planetFilterData" - so an
 * unknown class name in a save is a hard load failure. Instances of this class
 * are written into saves in TWO places: the intel manager (addIntel) and a hard
 * pin in sector memory under KEY. Deleting the class corrupts every save that
 * has ever smuggled.
 *
 * <p>It has ZERO instance fields and must keep it that way, so there is no
 * field drift to worry about either. The statics below are not serialized;
 * KEY is retained because PiratePatModPlugin's load-time purge needs it to
 * unset the sector-memory pin - removeIntel alone leaves the object reachable
 * and XStream keeps writing it into the save forever.
 *
 * <p>isHidden and shouldRemoveIntel are belt-and-braces: the purge in
 * PiratePatModPlugin.onGameLoad evicts every instance on load, but if one is
 * ever created or restored by a path we did not anticipate it stays off the
 * intel list and asks the intel manager to drop it.
 */
public class PersonalBountyIntel extends BaseIntelPlugin {

	/** Sector-memory pin from the old implementation. Unset by the load-time purge. */
	public static final String KEY = "$piratepat_bountyIntel";

	@Override
	public String getName() {
		return "A Price On Your Head";
	}

	@Override
	public boolean isHidden() {
		return true;
	}

	@Override
	public boolean shouldRemoveIntel() {
		return true;
	}
}
