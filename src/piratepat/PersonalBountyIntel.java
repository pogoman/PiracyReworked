package piratepat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The player's wanted list: per-faction personal bounties accrued by feeding
 * the pirate war chest at their markets, and what kind of hunters that money
 * is buying.
 */
public class PersonalBountyIntel extends BaseIntelPlugin {

	public static final String KEY = "$piratepat_bountyIntel";

	public static PersonalBountyIntel get() {
		return (PersonalBountyIntel) Global.getSector().getMemoryWithoutUpdate().get(KEY);
	}

	public static void ensureAdded() {
		if (get() != null) return;
		PersonalBountyIntel intel = new PersonalBountyIntel();
		Global.getSector().getMemoryWithoutUpdate().set(KEY, intel);
		Global.getSector().getIntelManager().addIntel(intel);
	}

	@Override
	public String getName() {
		return "A Price On Your Head";
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.INDEPENDENT).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_MAJOR_EVENT);
		tags.add(WarChestIntel.TAG_PATRONAGE);
		return tags;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);

		Color t = Misc.getTextColor();
		Color h = Misc.getHighlightColor();
		info.addPara("Total bounty: %s", 3f, t, h,
				Misc.getDGSCredits(PiratePatData.getTotalBounty()));
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color gray = Misc.getGrayColor();

		info.addPara("Someone has been feeding the pirate war chest through the black markets "
				+ "of the sector's ports - and while nothing can be proven well enough for open "
				+ "hostilities, the underworld talks. The factions concerned have quietly posted "
				+ "personal bounties on the smuggler responsible: you.", opad);

		Map<String, Float> bounties = PiratePatData.bounties();
		float min = PiratePatConfig.bountyActivationMin();

		java.util.List<Map.Entry<String, Float>> sorted =
				new ArrayList<Map.Entry<String, Float>>(bounties.entrySet());
		Collections.sort(sorted, new Comparator<Map.Entry<String, Float>>() {
			public int compare(Map.Entry<String, Float> a, Map.Entry<String, Float> b) {
				return (int) Math.signum(b.getValue() - a.getValue());
			}
		});

		if (sorted.isEmpty()) {
			info.addPara("No standing bounties. Your ledger is clean - or forgotten.", opad);
		} else {
			info.addPara("Standing bounties:", opad);
			for (Map.Entry<String, Float> entry : sorted) {
				FactionAPI faction = Global.getSector().getFaction(entry.getKey());
				String name = faction != null ? Misc.ucFirst(faction.getDisplayName()) : entry.getKey();
				Color c = entry.getValue() >= min ? neg : gray;
				String status = entry.getValue() >= min ? "" : " (below hunters' notice)";
				info.addPara(BULLET + name + ": %s" + status, 3f, c, h,
						Misc.getDGSCredits(entry.getValue()));
			}
		}

		float total = PiratePatData.getTotalBounty();
		if (total >= min) {
			float fp = Math.min(total / PiratePatConfig.bountyCreditsPerFP(),
					PiratePatConfig.bountyMaxFPPerFleet());
			info.addPara("Independent hunter fleets take these contracts, ambushing you with "
					+ "strength scaled to the bounty. Large bounties fund multiple fleets "
					+ "hunting concurrently.", opad);
			info.addPara(BULLET + "Estimated hunter fleet strength: up to ~%s fleet points each.",
					3f, h, "" + (int) fp);
		}

		float decay = PiratePatConfig.bountyDecayPerMonth();
		if (decay > 0) {
			info.addPara("Bounties fade by about %s per month if you stop adding to them. "
					+ "Memories are long, but not infinite.", opad, h,
					(int) Math.round(decay * 100f) + "%");
		}
	}
}
