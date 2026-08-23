package piratepat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * The pirate war economy balance sheet: chest, income, operating bases,
 * savings toward the next base, lifetime bookkeeping (including exactly how
 * much of it the player bankrolled), and the recent ledger.
 */
public class WarChestIntel extends BaseIntelPlugin {

	public static final String KEY = "$piratepat_intel";

	/** Custom intel tag: gives the war chest its own tab in the intel screen. */
	public static final String TAG_PATRONAGE = "Pirate Patronage";

	public static final int LEDGER_DISPLAY_LINES = 10;

	public static WarChestIntel get() {
		return (WarChestIntel) Global.getSector().getMemoryWithoutUpdate().get(KEY);
	}

	public static void ensureAdded() {
		if (get() != null) return;
		WarChestIntel intel = new WarChestIntel();
		Global.getSector().getMemoryWithoutUpdate().set(KEY, intel);
		Global.getSector().getIntelManager().addIntel(intel, true);
	}

	@Override
	public String getName() {
		return "Pirate War Chest";
	}

	@Override
	public String getIcon() {
		String crest = Global.getSector().getFaction(Factions.PIRATES).getCrest();
		if (crest != null) return crest;
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_MAJOR_EVENT);
		tags.add(TAG_PATRONAGE);
		return tags;
	}

	protected int countBases() {
		PatronageBaseManager mgr = PatronageBaseManager.get();
		if (mgr == null) return 0;
		return mgr.getBases().size();
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		info.addPara(getName(), tc, 0f);

		Color t = Misc.getTextColor();
		Color h = Misc.getHighlightColor();
		float initPad = 3f;

		if (PiratePatConfig.intelShowExact()) {
			info.addPara("War chest: %s", initPad, t, h,
					Misc.getDGSCredits(PiratePatData.getChest()));
		}
		info.addPara("Operating bases: %s", 0f, t, h, "" + countBases());
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();
		Color pos = Misc.getPositiveHighlightColor();
		Color gray = Misc.getGrayColor();

		info.addPara("The black market is pirate infrastructure. Every credit that moves "
				+ "through it - yours included - feeds a war chest that finances raids and "
				+ "new bases. Raids cost money to launch; successful raids pay it back with "
				+ "interest. A flush underworld expands. A starved one goes quiet.", opad);

		PatronageBaseManager mgr = PatronageBaseManager.get();
		int bases = countBases();
		float chest = PiratePatData.getChest();

		if (PiratePatConfig.intelShowExact()) {
			info.addPara("War chest: %s", opad, h, Misc.getDGSCredits(chest));
			float income = PiratePatConfig.incomePerBasePerMonth() * bases;
			info.addPara("Underworld income: %s per month from %s operating bases.", 3f, h,
					Misc.getDGSCredits(income), "" + bases);
			if (mgr != null && bases < PiratePatConfig.maxBases()) {
				float cost = mgr.getCurrentBaseCost();
				int pct = (int) Math.min(100f, chest / cost * 100f);
				info.addPara("Next base: %s (%s funded)", 3f, h,
						Misc.getDGSCredits(cost), pct + "%");
			}
		} else {
			String flavor;
			if (bases == 0 && chest < PiratePatConfig.baseCost() * 0.25f) {
				flavor = "The underworld lies dormant... for now.";
			} else if (chest < PiratePatConfig.baseCost() * 0.5f) {
				flavor = "The underworld scrapes by.";
			} else {
				flavor = "The underworld prospers.";
			}
			info.addPara(flavor, opad);
		}

		// bases: known ones by name, hidden ones as an anonymous count
		if (mgr != null && bases > 0) {
			int hidden = 0;
			info.addPara("Operating bases:", opad);
			for (PirateBaseIntel base : mgr.getBases()) {
				if (base.isPlayerVisible()) {
					info.addPara(BULLET + base.getSystem().getNameWithLowercaseTypeShort()
							+ ": tier %s", 3f, h, "" + (base.getTier().ordinal() + 1));
				} else {
					hidden++;
				}
			}
			if (hidden > 0) {
				info.addPara(BULLET + "Signs point to %s more hidden " + (hidden == 1 ? "base" : "bases")
						+ ".", 3f, gray, h, "" + hidden);
			}
		} else if (bases == 0) {
			info.addPara("No operating pirate bases. Only fresh money can finance a return.",
					opad, pos, "Only fresh money");
		}

		// lifetime bookkeeping
		float player = PiratePatData.getLifetimePlayerContribution();
		int sharePct = Math.round(PiratePatData.getPlayerShare() * 100f);
		if (player > 0) {
			info.addPara("You have contributed %s to the pirate war economy - %s of its "
					+ "lifetime income.", opad, neg,
					Misc.getDGSCredits(player), sharePct + "%");
		} else {
			info.addPara("You have contributed nothing to the pirate war economy. So far.", opad);
		}

		int launched = PiratePatData.getRaidsLaunched();
		if (launched > 0) {
			info.addPara("Raids financed: %s, of which %s succeeded and %s were repelled. "
					+ "Bases established: %s.", 3f, h,
					"" + launched,
					"" + PiratePatData.getRaidsSucceeded(),
					"" + PiratePatData.getRaidsDefeated(),
					"" + PiratePatData.getBasesPurchased());
		}

		// recent ledger
		List<String> ledger = PiratePatData.ledger();
		if (!ledger.isEmpty()) {
			info.addPara("Recent ledger:", opad);
			int shown = 0;
			for (String line : ledger) {
				info.addPara(BULLET + line, gray, 3f);
				shown++;
				if (shown >= LEDGER_DISPLAY_LINES) break;
			}
		}

		info.addPara("Repelling raids denies the pirates their returns; destroying bases "
				+ "destroys their investment and their income. Trading on the black market "
				+ "does the opposite.", opad, pos, "Repelling raids", "destroying bases");
	}
}
