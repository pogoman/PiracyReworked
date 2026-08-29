package piratepat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.campaign.ids.Items;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BaseBarEventWithPerson;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * The fixer: an underworld broker found in bars at markets with a black
 * market, taking commissions for goods no legal market sells - colony-grade
 * industrial equipment, and blueprints "liberated" from faction archives.
 * The deposit is paid up front and goes straight into the pirate war chest;
 * fulfillment is a real raid, launched by an operating war-chest base
 * against whichever system holds the goods. The broker deals even when the
 * underworld has no bases - the deposit is then quite literally financing
 * their reconstruction, and the order waits until the network can sail.
 */
public class BrokerBarEvent extends BaseBarEventWithPerson {

	public static enum OptionId {
		MENU,
		EQUIPMENT,
		BLUEPRINTS,
		CONFIRM,
		BACK,
		LEAVE,
	}

	public static class CatalogEntry {
		public String itemId;
		public String itemParam;
		public String name;
		public int price;
		public CatalogEntry(String itemId, String itemParam, String name, int price) {
			this.itemId = itemId;
			this.itemParam = itemParam;
			this.name = name;
			this.price = price;
		}
	}

	protected transient CatalogEntry selected = null;

	@Override
	public boolean shouldShowAtMarket(MarketAPI market) {
		if (!super.shouldShowAtMarket(market)) return false;
		if (!PiratePatConfig.enabled() || !PiratePatConfig.brokerEnabled()) return false;
		if (!market.hasSubmarket(Submarkets.SUBMARKET_BLACK)) return false;
		if (CommissionIntel.activeCount() >= PiratePatConfig.brokerMaxConcurrent()) return false;
		regen(market);
		return true;
	}

	public static int countCommissionCapableBases() {
		PatronageBaseManager mgr = PatronageBaseManager.get();
		if (mgr == null) return 0;
		int count = 0;
		for (PirateBaseIntel base : mgr.getBases()) {
			if (base instanceof PatronageBaseIntel && !base.isEnding() && !base.isEnded()) {
				count++;
			}
		}
		return count;
	}

	@Override
	public void addPromptAndOption(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
		super.addPromptAndOption(dialog, memoryMap);
		regen(dialog.getInteractionTarget().getMarket());
		dialog.getTextPanel().addPara("A " + getManOrWoman() + " with careful eyes holds court "
				+ "in a corner booth. Nobody seems to pay, but " + getHisOrHer()
				+ " tab never closes, and people keep stopping by to whisper.");
		dialog.getOptionPanel().addOption("See what the fixer in the corner booth can source",
				this);
	}

	@Override
	public void init(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
		super.init(dialog, memoryMap);
		done = false;
		dialog.getVisualPanel().showPersonInfo(person, true);
		optionSelected(null, OptionId.MENU);
	}

	@Override
	public void optionSelected(String optionText, Object optionData) {
		if (optionData instanceof CatalogEntry) {
			showConfirm((CatalogEntry) optionData);
			return;
		}
		if (!(optionData instanceof OptionId)) return;

		OptionId option = (OptionId) optionData;
		switch (option) {
		case MENU:
		case BACK:
			showMenu(option == OptionId.MENU);
			break;
		case EQUIPMENT:
			showCatalog(buildEquipmentCatalog(),
					"\"Industrial hardware. Domain-era, functional, provenance best left "
					+ "undiscussed. You name it, we find where it lives.\"");
			break;
		case BLUEPRINTS:
			showCatalog(buildBlueprintOffers(),
					"\"Blueprints. Faction archives are better guarded than their vaults, "
					+ "so the menu is what my people can reach this month.\"");
			break;
		case CONFIRM:
			placeOrder();
			break;
		case LEAVE:
			done = true;
			break;
		}
	}

	protected void showMenu(boolean withIntro) {
		options.clearOptions();
		if (withIntro) {
			text.addPara("The fixer waves you into the booth without looking up. \"You have "
					+ "the walk of someone shopping for what isn't sold. Good news: nothing "
					+ "is truly off the market. My associates can... source things. Industrial "
					+ "equipment nobody will sell you. Blueprints nobody will license you.\"");
			text.addPara("\"Terms are simple. Full deposit up front - acquisition is expensive. "
					+ "Sixty to a hundred and twenty days for delivery, to storage right here. "
					+ "And no questions from either side of the table about where it comes "
					+ "from.\"");
			int active = CommissionIntel.activeCount();
			if (active > 0) {
				text.addPara("You currently have " + active + " open "
						+ (active == 1 ? "commission" : "commissions") + " with the network.",
						Misc.getHighlightColor(), "" + active);
			}
		}
		options.addOption("Ask about industrial equipment", OptionId.EQUIPMENT);
		options.addOption("Ask about blueprints", OptionId.BLUEPRINTS);
		options.addOption("Leave the booth", OptionId.LEAVE);
	}

	protected void showCatalog(List<CatalogEntry> catalog, String pitch) {
		options.clearOptions();
		text.addPara(pitch);
		if (catalog.isEmpty()) {
			text.addPara("\"Nothing on the menu for you this month. Check back - inventory "
					+ "moves.\"");
		}
		float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
		for (CatalogEntry entry : catalog) {
			options.addOption(entry.name + " - " + Misc.getDGSCredits(entry.price), entry);
			if (entry.price > credits) {
				options.setEnabled(entry, false);
				options.setTooltip(entry, "You can't cover the deposit.");
			}
		}
		options.addOption("Ask about something else", OptionId.BACK);
		options.addOption("Leave the booth", OptionId.LEAVE);
	}

	protected void showConfirm(CatalogEntry entry) {
		selected = entry;
		options.clearOptions();
		text.addPara("\"" + entry.name + ". It can be had.\" The fixer names the figure: %s, "
				+ "all of it up front.", Misc.getHighlightColor(),
				Misc.getDGSCredits(entry.price));
		text.addPara("\"To be clear about the fine print, since you look like the careful "
				+ "type: nobody manufactures this for us. It will be taken from whoever has "
				+ "it, by people whose wages your deposit pays. If their operation goes badly, "
				+ "you get part of your money back and nobody got anything. And the people it "
				+ "is taken from tend to form opinions about who paid for the taking.\"");
		if (CommissionIntel.SHIP_BP.equals(entry.itemId)
				|| CommissionIntel.WEAPON_BP.equals(entry.itemId)) {
			text.addPara("You have no doubt the pirates will keep a copy of any blueprint "
					+ "that passes through their hands.", Misc.getNegativeHighlightColor(),
					"keep a copy");
		}
		if (countCommissionCapableBases() <= 0) {
			text.addPara("\"One caveat, in the spirit of honest dealing: the network is... "
					+ "rebuilding, just now. No operating bases. Your deposit will help with "
					+ "that, as it happens. But delivery waits on the fleet that fetches it, "
					+ "so - patience.\"", Misc.getHighlightColor(), "rebuilding");
		}
		options.addOption("Pay the deposit of " + Misc.getDGSCredits(entry.price),
				OptionId.CONFIRM);
		options.addOption("Reconsider", OptionId.BACK);
	}

	protected void placeOrder() {
		if (selected == null) return;
		CargoAPI cargo = Global.getSector().getPlayerFleet().getCargo();
		if (cargo.getCredits().get() < selected.price) {
			text.addPara("You can't cover the deposit.");
			showMenu(false);
			return;
		}
		if (CommissionIntel.activeCount() >= PiratePatConfig.brokerMaxConcurrent()) {
			text.addPara("\"One thing at a time, captain. The network's hands are full with "
					+ "your existing business.\"");
			showMenu(false);
			return;
		}

		cargo.getCredits().subtract(selected.price);
		AddRemoveCommodity.addCreditsLossText(selected.price, text);
		PiratePatData.addCommissionDeposit(selected.price, selected.name, market.getName());
		new CommissionIntel(market, selected.itemId, selected.itemParam, selected.name,
				selected.price);

		text.addPara("The fixer taps a code into a battered tripad and the money is gone. "
				+ "\"Pleasure. Watch your intel feed - my people will be in touch when there's "
				+ "something worth telling you.\"");
		BarEventManager.getInstance().notifyWasInteractedWith(this);
		options.clearOptions();
		options.addOption("Leave the booth", OptionId.LEAVE);
	}

	// --- catalog construction ---

	protected List<CatalogEntry> buildEquipmentCatalog() {
		List<CatalogEntry> catalog = new ArrayList<CatalogEntry>();
		for (SpecialItemSpecAPI spec : Global.getSettings().getAllSpecialItemSpecs()) {
			if (!spec.hasTag(Items.TAG_COLONY_ITEM)) continue;
			int price = (int) (priceForSpecial(spec.getId(), null)
					* PiratePatConfig.brokerPriceMult());
			if (price <= 0) continue;
			catalog.add(new CatalogEntry(spec.getId(), null, spec.getName(), price));
		}
		Collections.sort(catalog, new Comparator<CatalogEntry>() {
			public int compare(CatalogEntry a, CatalogEntry b) {
				return a.price - b.price;
			}
		});
		return catalog;
	}

	/**
	 * A small rotating menu of rare blueprints, re-seeded per market and
	 * month - the network can only reach so many archives at a time.
	 */
	protected List<CatalogEntry> buildBlueprintOffers() {
		List<CatalogEntry> offers = new ArrayList<CatalogEntry>();
		long months = Global.getSector().getClock().getCycle() * 12L
				+ Global.getSector().getClock().getMonth();
		Random seeded = new Random(market.getId().hashCode() * 31L + months);
		WeightedRandomPicker<CatalogEntry> picker = new WeightedRandomPicker<CatalogEntry>(seeded);

		for (ShipHullSpecAPI spec : Global.getSettings().getAllShipHullSpecs()) {
			if (!spec.hasTag(Items.TAG_RARE_BP)) continue;
			// fighter hulls are LPCs, not ship blueprints
			if (spec.getHullSize() == com.fs.starfarer.api.combat.ShipAPI.HullSize.FIGHTER) continue;
			if (Global.getSector().getPlayerFaction().knowsShip(spec.getHullId())) continue;
			int price = (int) (priceForSpecial(CommissionIntel.SHIP_BP, spec.getHullId())
					* PiratePatConfig.brokerPriceMult());
			if (price <= 0) continue;
			picker.add(new CatalogEntry(CommissionIntel.SHIP_BP, spec.getHullId(),
					spec.getHullName() + " blueprint", price), 1f);
		}
		for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
			if (!spec.hasTag(Items.TAG_RARE_BP)) continue;
			if (Global.getSector().getPlayerFaction().knowsWeapon(spec.getWeaponId())) continue;
			int price = (int) (priceForSpecial(CommissionIntel.WEAPON_BP, spec.getWeaponId())
					* PiratePatConfig.brokerPriceMult());
			if (price <= 0) continue;
			picker.add(new CatalogEntry(CommissionIntel.WEAPON_BP, spec.getWeaponId(),
					spec.getWeaponName() + " blueprint", price), 1f);
		}

		int count = PiratePatConfig.brokerBpOffers();
		for (int i = 0; i < count && !picker.isEmpty(); i++) {
			offers.add(picker.pickAndRemove());
		}
		return offers;
	}

	/**
	 * Price a special item the way the game itself would: via the item
	 * plugin (which for blueprints folds in the hull/weapon value), falling
	 * back to the spec's base price.
	 */
	public static int priceForSpecial(String itemId, String param) {
		try {
			CargoAPI temp = Global.getFactory().createCargo(true);
			temp.addSpecial(new SpecialItemData(itemId, param), 1);
			for (CargoStackAPI stack : temp.getStacksCopy()) {
				if (stack.isSpecialStack() && stack.getPlugin() != null) {
					return stack.getPlugin().getPrice(null, null);
				}
			}
		} catch (Throwable t) {
			// fall through to spec base price
		}
		SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(itemId);
		return spec != null ? (int) spec.getBasePrice() : 0;
	}

	@Override
	protected String getPersonFaction() {
		return com.fs.starfarer.api.impl.campaign.ids.Factions.INDEPENDENT;
	}
}
