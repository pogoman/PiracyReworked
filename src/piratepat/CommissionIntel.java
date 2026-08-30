package piratepat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import java.awt.Color;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.impl.items.BlueprintProviderItem;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.impl.campaign.DelayedBlueprintLearnScript;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * A commissioned acquisition: the player paid an underworld fixer to source
 * something no legal market sells. The deposit went straight into the war
 * chest; the network's operating bases do the sourcing - by launching a very
 * real raid against whichever unlucky system holds the goods. Success
 * delivers the item to storage at the market where the order was placed;
 * a repelled raid refunds part of the deposit.
 *
 * The most patron-like act in the mod, and priced accordingly on the
 * personal ledger: the full deposit counts as lifetime contribution, and the
 * raided faction's personal bounty on the player rises by a cut of it - a
 * raid is loud, and the underworld talks.
 */
public class CommissionIntel extends BaseIntelPlugin {

	public static Logger log = Global.getLogger(CommissionIntel.class);

	public static final String SHIP_BP = "ship_bp";
	public static final String WEAPON_BP = "weapon_bp";

	public static enum CommissionState {
		SOURCING,   // waiting for a base to mount the raid
		RAIDING,    // raid in flight
		DELIVERING, // raid succeeded, goods in transit
		DELIVERED,  // done
		REFUNDED,   // raid repelled or never mounted
	}

	protected String itemId;
	protected String itemParam; // hull/weapon id for blueprints, null otherwise
	protected String itemName;
	protected float deposit;
	protected MarketAPI market; // where the order was placed and will be delivered
	protected MarketAPI sourceMarket; // equipment: the colony that actually holds the item (null for blueprints)

	protected CommissionState state = CommissionState.SOURCING;
	protected float sourcingDays;
	protected float stallDays = 0f;
	protected float raidDays = 0f;
	protected float deliveryDays;
	protected float elapsed = 0f;

	protected String raidTargetName = null;
	protected String victimFactionId = null;

	protected float launchCheckTimer = 0f; // throttle system-scan launch attempts

	public CommissionIntel(MarketAPI market, String itemId, String itemParam,
			String itemName, float deposit, MarketAPI sourceMarket) {
		this.market = market;
		this.itemId = itemId;
		this.itemParam = itemParam;
		this.itemName = itemName;
		this.deposit = deposit;
		this.sourceMarket = sourceMarket;

		Random random = new Random();
		sourcingDays = 5f + random.nextFloat() * 10f;
		deliveryDays = 10f + random.nextFloat() * 10f;

		Global.getSector().addScript(this);
		Global.getSector().getIntelManager().addIntel(this);
	}

	public static boolean isOpen(CommissionIntel intel) {
		return !intel.isEnding() && !intel.isEnded()
				&& (intel.state == CommissionState.SOURCING || intel.state == CommissionState.RAIDING
					|| intel.state == CommissionState.DELIVERING);
	}

	public static int activeCount() {
		int count = 0;
		for (IntelInfoPlugin curr : Global.getSector().getIntelManager().getIntel(CommissionIntel.class)) {
			if (isOpen((CommissionIntel) curr)) count++;
		}
		return count;
	}

	/**
	 * itemId:itemParam keys of every order still in flight, so catalogs can
	 * avoid offering something the player has already paid to acquire.
	 */
	public static Set<String> activeItemKeys() {
		Set<String> keys = new LinkedHashSet<String>();
		for (IntelInfoPlugin curr : Global.getSector().getIntelManager().getIntel(CommissionIntel.class)) {
			CommissionIntel intel = (CommissionIntel) curr;
			if (!isOpen(intel)) continue;
			keys.add(itemKey(intel.itemId, intel.itemParam));
		}
		return keys;
	}

	public static String itemKey(String itemId, String itemParam) {
		return itemId + ":" + (itemParam == null ? "" : itemParam);
	}

	@Override
	protected void advanceImpl(float amount) {
		super.advanceImpl(amount);
		if (isEnding() || isEnded()) return;
		float days = Global.getSector().getClock().convertToDays(amount);
		elapsed += days;

		switch (state) {
		case SOURCING:
			if (!PiratePatConfig.enabled()) {
				refund(PiratePatConfig.brokerRefundUnserved(), "the network has gone quiet");
				return;
			}
			if (elapsed < sourcingDays) return;
			launchCheckTimer += days;
			if (launchCheckTimer >= 2f) {
				launchCheckTimer = 0f;
				if (tryLaunchRaid()) return;
			}
			// the stall clock only runs while the network HAS a base that
			// could sail but isn't taking the job. With zero bases the order
			// simply waits - the deposit sits in the war chest, quite
			// literally financing the reconstruction that will fulfill it.
			if (BrokerDialog.countCommissionCapableBases() > 0) {
				stallDays += days;
				if (stallDays > PiratePatConfig.brokerStallDays()) {
					refund(PiratePatConfig.brokerRefundUnserved(),
							"the network could not mount the operation");
					return;
				}
			}
			// absolute failsafe so an order can't hang forever
			if (elapsed > 365f) {
				refund(PiratePatConfig.brokerRefundUnserved(),
						"the network never recovered enough to mount the operation");
			}
			break;
		case RAIDING:
			raidDays += days;
			// failsafe: the raid should always report back through the base,
			// but if the base died in some way that swallowed the report,
			// don't leave the commission hanging forever
			if (raidDays > 150f) {
				refund(PiratePatConfig.brokerRefundFailed(), "the operation came apart");
			}
			break;
		case DELIVERING:
			raidDays += days; // reusing as delivery clock reset on transition
			if (raidDays >= deliveryDays) {
				deliver();
			}
			break;
		default:
			break;
		}
	}

	/**
	 * Find a war-chest base with no raid in flight and point it at a system
	 * worth raiding. Returns true if a raid launched.
	 */
	protected boolean tryLaunchRaid() {
		PatronageBaseManager mgr = PatronageBaseManager.get();
		if (mgr == null) return false;

		// the strongest free base takes the job - the deposit deserves the
		// network's best crack at it
		PatronageBaseIntel base = null;
		for (PirateBaseIntel curr : mgr.getBases()) {
			if (!(curr instanceof PatronageBaseIntel)) continue; // adopted vanilla bases can't carry commissions
			PatronageBaseIntel pb = (PatronageBaseIntel) curr;
			if (pb.isEnding() || pb.isEnded()) continue;
			if (!pb.canCarryCommission()) continue;
			if (base == null || pb.getTier().ordinal() > base.getTier().ordinal()) base = pb;
		}
		if (base == null) return false;

		StarSystemAPI target;
		if (sourceMarket != null) {
			// the goods live at a specific colony - if they're gone (item
			// removed, colony decivilized, player moved in next door), the
			// order can't be served
			if (!sourceMarket.isInEconomy() || findHoldingIndustry() == null
					|| !Misc.getMarketsInLocation(sourceMarket.getContainingLocation(),
							Factions.PLAYER).isEmpty()) {
				refund(PiratePatConfig.brokerRefundUnserved(),
						"the goods are no longer where the network thought");
				return true; // handled - stop trying
			}
			target = sourceMarket.getStarSystem();
			if (target == null) return false;
		} else {
			target = pickTarget(base);
		}
		if (target == null) return false;

		// the victim: the colony being robbed, or for blueprint jobs whoever
		// holds the biggest hostile market in the target system
		MarketAPI biggest = sourceMarket;
		if (biggest == null) {
			for (MarketAPI curr : Misc.getMarketsInLocation(target)) {
				if (curr.isHidden()) continue;
				if (UnderworldTithe.isOutsideUnderworldEconomy(curr.getFaction())) continue;
				if (!curr.getFaction().isHostileTo(base.getFactionForUIColors())) continue;
				if (biggest == null || curr.getSize() > biggest.getSize()) biggest = curr;
			}
		}

		if (!base.launchCommissionRaid(target, this)) return false;

		state = CommissionState.RAIDING;
		raidDays = 0f;
		raidTargetName = target.getNameWithNoType();
		if (biggest != null && !biggest.getFaction().isPlayerFaction()
				&& !Misc.isPirateFaction(biggest.getFaction())) {
			victimFactionId = biggest.getFactionId();
			if (PiratePatConfig.bountyEnabled()) {
				float bounty = deposit * PiratePatConfig.brokerBountyFraction();
				if (bounty > 0) {
					PiratePatData.raiseBounty(victimFactionId, bounty);
					PiratePatData.addLedger("Word gets around: " + biggest.getFaction().getDisplayName()
							+ " suspects who paid for the " + raidTargetName + " raid", 0f);
				}
			}
		}
		sendUpdateIfPlayerHasIntel(new Object(), false);
		if (PiratePatConfig.debugLogging()) {
			log.info("Commission raid launched against " + raidTargetName + " for " + itemName);
		}
		return true;
	}

	protected StarSystemAPI pickTarget(PatronageBaseIntel base) {
		WeightedRandomPicker<StarSystemAPI> picker = new WeightedRandomPicker<StarSystemAPI>();
		for (StarSystemAPI system : Global.getSector().getStarSystems()) {
			if (system == base.getSystem()) continue;
			if (!Misc.getMarketsInLocation(system, Factions.PLAYER).isEmpty()) continue;
			float weight = 0f;
			for (MarketAPI curr : Misc.getMarketsInLocation(system)) {
				if (curr.isHidden()) continue;
				// machine/outsider factions (the Threat hive, Remnants...)
				// hold nothing a fence can move - not raid material
				if (UnderworldTithe.isOutsideUnderworldEconomy(curr.getFaction())) continue;
				if (curr.getFaction().isHostileTo(base.getFactionForUIColors())) {
					weight += curr.getSize();
				}
			}
			// somebody has to actually have the goods: bigger worlds, better fences
			if (weight >= 4f) picker.add(system, weight);
		}
		return picker.pick();
	}

	/** Called by the base when the commissioned raid resolves. */
	public void reportRaidOutcome(boolean success) {
		if (state != CommissionState.RAIDING) return;
		if (!success) {
			refund(PiratePatConfig.brokerRefundFailed(), "the raid on " + raidTargetName
					+ " was repelled");
			return;
		}

		// for equipment, "the raid succeeded" isn't enough - the raiders must
		// have actually cracked the colony holding the goods (a raid can
		// succeed by hitting a softer neighbor), and the item is then TAKEN:
		// the colony's industry loses it for good
		if (sourceMarket != null) {
			boolean raidedTheSource = Misc.flagHasReason(
					sourceMarket.getMemoryWithoutUpdate(),
					com.fs.starfarer.api.impl.campaign.ids.MemFlags.RECENTLY_RAIDED,
					Factions.PIRATES);
			com.fs.starfarer.api.campaign.econ.Industry holder = findHoldingIndustry();
			if (!raidedTheSource || holder == null) {
				refund(PiratePatConfig.brokerRefundFailed(),
						"the raiders couldn't crack the vault at "
						+ sourceMarket.getName());
				return;
			}
			holder.setSpecialItem(null); // theft, not manufacture
		}

		state = CommissionState.DELIVERING;
		raidDays = 0f;
		sendUpdateIfPlayerHasIntel(new Object(), false);
	}

	/** The industry at the source colony that still has the ordered item installed. */
	protected com.fs.starfarer.api.campaign.econ.Industry findHoldingIndustry() {
		if (sourceMarket == null) return null;
		for (com.fs.starfarer.api.campaign.econ.Industry ind : sourceMarket.getIndustries()) {
			SpecialItemData item = ind.getSpecialItem();
			if (item != null && itemId.equals(item.getId())) return ind;
		}
		return null;
	}

	protected void deliver() {
		state = CommissionState.DELIVERED;

		SpecialItemData data = new SpecialItemData(itemId, itemParam);
		boolean toStorage = false;
		if (market != null && market.isInEconomy()) {
			SubmarketAPI storage = market.getSubmarket(Submarkets.SUBMARKET_STORAGE);
			if (storage != null && storage.getCargo() != null) {
				storage.getCargo().addSpecial(data, 1);
				toStorage = true;
			}
		}
		if (!toStorage) {
			// market gone (decivilized, bombarded...) - the courier finds the fleet
			Global.getSector().getPlayerFleet().getCargo().addSpecial(data, 1);
		}

		// the pirates raided for it - of course they kept a copy
		if (isBlueprint() && itemParam != null) {
			DelayedBlueprintLearnScript learn = new DelayedBlueprintLearnScript(Factions.PIRATES, 1f);
			if (SHIP_BP.equals(itemId)) learn.getShips().add(itemParam);
			if (WEAPON_BP.equals(itemId)) learn.getWeapons().add(itemParam);
			Global.getSector().addScript(learn);
		}

		PiratePatData.addLedger("Commission delivered: " + itemName
				+ (toStorage && market != null ? " (storage, " + market.getName() + ")" : ""), 0f);
		deliveredToStorage = toStorage;
		sendUpdateIfPlayerHasIntel(new Object(), false);
		// linger long enough that the player can find where it went
		endAfterDelay(30f);
	}

	protected boolean deliveredToStorage = false;

	protected void refund(float fraction, String reason) {
		state = CommissionState.REFUNDED;
		float refund = deposit * fraction;
		if (refund > 0) {
			Global.getSector().getPlayerFleet().getCargo().getCredits().add(refund);
			// the broker honors the refund whether or not the chest can cover it
			PiratePatData.spendUpTo(refund, "Commission refunded: " + itemName);
		}
		refundReason = reason;
		refundAmount = refund;
		sendUpdateIfPlayerHasIntel(new Object(), false);
		endAfterDelay(10f);
	}

	protected String refundReason = null;
	protected float refundAmount = 0f;

	public boolean isBlueprint() {
		return SHIP_BP.equals(itemId) || WEAPON_BP.equals(itemId);
	}

	@Override
	protected void notifyEnded() {
		super.notifyEnded();
		Global.getSector().removeScript(this);
	}

	@Override
	public String getName() {
		return "Commission - " + itemName;
	}

	@Override
	public String getIcon() {
		SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(itemId);
		if (spec != null && spec.getIconName() != null) return spec.getIconName();
		return super.getIcon();
	}

	@Override
	public Set<String> getIntelTags(SectorMapAPI map) {
		Set<String> tags = new LinkedHashSet<String>();
		tags.add(Tags.INTEL_MISSIONS);
		tags.add(WarChestIntel.TAG_PATRONAGE);
		return tags;
	}

	@Override
	public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
		Color tc = getTitleColor(mode);
		Color t = Misc.getTextColor();
		Color h = Misc.getHighlightColor();
		info.addPara(getName(), tc, 0f);
		switch (state) {
		case SOURCING:
			info.addPara("The network is sourcing your order", t, 3f);
			break;
		case RAIDING:
			info.addPara("A raid is underway against %s", 3f, t, h, raidTargetName);
			break;
		case DELIVERING:
			info.addPara("In transit to %s", 3f, t, h,
					market != null ? market.getName() : "you");
			break;
		case DELIVERED:
			if (deliveredToStorage && market != null) {
				info.addPara("Delivered - in storage at %s", 3f, t, h, market.getName());
			} else {
				info.addPara("Delivered to your fleet", t, 3f);
			}
			break;
		case REFUNDED:
			info.addPara("Order failed - %s refunded", 3f, t, h,
					Misc.getDGSCredits(refundAmount));
			break;
		}
	}

	@Override
	public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
		float opad = 10f;
		Color h = Misc.getHighlightColor();
		Color neg = Misc.getNegativeHighlightColor();

		info.addPara("You paid %s to an underworld fixer to acquire: %s.", opad, h,
				Misc.getDGSCredits(deposit), itemName);
		info.addPara("The goods are not bought - they are taken. Your deposit finances "
				+ "the operation, and the sector's ledger against you grows by every "
				+ "credit of it.", opad);

		switch (state) {
		case SOURCING:
			if (BrokerDialog.countCommissionCapableBases() <= 0) {
				info.addPara("The network has no operating bases to mount the acquisition - "
						+ "your deposit is financing its reconstruction. The order will wait "
						+ "until the underworld can sail again.", opad,
						Misc.getHighlightColor(), "financing its reconstruction");
			} else {
				info.addPara("The network is working out where such a thing can be found - "
						+ "and who will be made to part with it.", opad);
			}
			break;
		case RAIDING:
			if (sourceMarket != null) {
				info.addPara("A pirate raid is underway against %s - the goods live at %s, "
						+ "and the raiders must crack that colony specifically. If they do, "
						+ "your order ships with the spoils.", opad, h, raidTargetName,
						sourceMarket.getName());
			} else {
				info.addPara("A pirate raid is underway against %s. If it succeeds, your "
						+ "order ships with the spoils.", opad, h, raidTargetName);
			}
			break;
		case DELIVERING:
			info.addPara("The raid on %s succeeded. Your order is in transit to %s"
					+ " - it will be placed in local storage.", opad, h,
					raidTargetName, market != null ? market.getName() : "your fleet");
			break;
		case DELIVERED:
			if (deliveredToStorage && market != null) {
				info.addPara("Delivered to storage at %s - check the storage tab there "
						+ "(local storage may want its access fee paid).", opad, h,
						market.getName());
			} else {
				info.addPara("Delivered directly to your fleet.", opad);
			}
			if (isBlueprint()) {
				info.addPara("The pirates kept a copy of the blueprint for themselves, "
						+ "naturally.", 3f, neg, "kept a copy");
			}
			break;
		case REFUNDED:
			info.addPara("The order failed - " + refundReason + ". The broker returned "
					+ "%s of your deposit; the rest is gone.", opad, h,
					Misc.getDGSCredits(refundAmount));
			break;
		}

		if (victimFactionId != null && state != CommissionState.SOURCING) {
			info.addPara("Word of who bankrolled the raid travels in the wrong circles - "
					+ "your standing bounty with the victims has grown.", opad, neg,
					"your standing bounty");
		}
	}
}
