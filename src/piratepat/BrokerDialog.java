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
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.campaign.ids.Items;
import com.fs.starfarer.api.impl.campaign.intel.bases.PirateBaseIntel;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import com.fs.starfarer.api.impl.campaign.rulecmd.FireAll;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

/**
 * The broker conversation: an underworld contact offering to source goods no
 * legal market sells - colony-grade industrial equipment, and blueprints
 * "liberated" from faction archives. Runs as a temporary dialog plugin
 * swapped in over the contact conversation (the same mechanism vanilla's bar
 * uses via BarEventDialogPlugin), and hands control back to the rules dialog
 * when the player is done.
 *
 * The deposit is paid up front and goes straight into the pirate war chest;
 * fulfillment is a real raid, launched by an operating war-chest base
 * against whichever system holds the goods. The contact deals even when the
 * underworld has no bases - the deposit is then quite literally financing
 * their reconstruction, and the order waits until the network can sail.
 */
public class BrokerDialog implements InteractionDialogPlugin {

	public static enum OptionId {
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
		public MarketAPI source;   // equipment only: the colony that actually holds it
		public float defense;      // its war-sim defense strength at pricing time
		public boolean feasible;   // can the network's best base plausibly crack it
		public CatalogEntry(String itemId, String itemParam, String name, int price) {
			this.itemId = itemId;
			this.itemParam = itemParam;
			this.name = name;
			this.price = price;
		}
	}

	protected InteractionDialogAPI dialog;
	protected InteractionDialogPlugin originalPlugin;
	protected Map<String, MemoryAPI> memoryMap;
	protected MarketAPI market;
	protected PersonAPI person;
	protected TextPanelAPI text;
	protected OptionPanelAPI options;
	protected CatalogEntry selected = null;
	protected int beyondReach = 0; // catalog entries filtered by the contact's importance

	/**
	 * What this contact's network can reach, priced in final commission
	 * credits: the cap doubles with each importance level, and a VERY_HIGH
	 * contact can get anything. Mirrors how vanilla scales contact mission
	 * quality and gates missions by PersonImportance.
	 */
	public static int priceCapFor(PersonImportance importance) {
		if (importance == null) importance = PersonImportance.VERY_LOW;
		if (importance == PersonImportance.VERY_HIGH) return Integer.MAX_VALUE;
		return (int) (PiratePatConfig.brokerImportanceCapBase() * (1 << importance.ordinal()));
	}

	protected PersonImportance importance() {
		if (person != null && person.getImportance() != null) return person.getImportance();
		return PersonImportance.VERY_LOW;
	}

	/** Swap the broker conversation in over the current (rules) dialog. */
	public static void begin(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			PersonAPI person, MarketAPI market) {
		BrokerDialog plugin = new BrokerDialog();
		plugin.originalPlugin = dialog.getPlugin();
		plugin.memoryMap = memoryMap;
		plugin.person = person;
		plugin.market = market;
		dialog.setPlugin(plugin);
		plugin.init(dialog);
	}

	public void init(InteractionDialogAPI dialog) {
		this.dialog = dialog;
		text = dialog.getTextPanel();
		options = dialog.getOptionPanel();
		showMenu(true);
	}

	public void optionSelected(String optionText, Object optionData) {
		if (optionText != null) {
			dialog.addOptionSelectedText(optionData);
		}
		if (optionData instanceof CatalogEntry) {
			showConfirm((CatalogEntry) optionData);
			return;
		}
		if (!(optionData instanceof OptionId)) return;

		switch ((OptionId) optionData) {
		case BACK:
			showMenu(false);
			break;
		case EQUIPMENT:
			showCatalog(buildEquipmentCatalog(),
					"\"Industrial hardware. Domain-era, functional, currently in someone "
					+ "else's possession - the network keeps track of where every piece "
					+ "lives, and what it's guarded by. The figure covers the taking.\"");
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
			finish();
			break;
		}
	}

	/** Hand control back to the contact's rules dialog. */
	protected void finish() {
		options.clearOptions();
		dialog.setPlugin(originalPlugin);
		FireAll.fire(null, dialog, memoryMap, "PopulateOptions");
	}

	protected void showMenu(boolean withIntro) {
		options.clearOptions();
		if (withIntro) {
			text.addPara("Your contact leans in, voice dropping. \"Ah - shopping for what "
					+ "isn't sold. Good news: nothing is truly off the market. The network "
					+ "can... source things. Industrial equipment nobody will sell you. "
					+ "Blueprints nobody will license you.\"");
			text.addPara("\"Terms are simple. Full deposit up front - acquisition is "
					+ "expensive. Sixty to a hundred and twenty days for delivery, to "
					+ "storage right here. And no questions from either side of the table "
					+ "about where it comes from.\"");
			int active = CommissionIntel.activeCount();
			if (active > 0) {
				text.addPara("You currently have " + active + " open "
						+ (active == 1 ? "commission" : "commissions") + " with the network.",
						Misc.getHighlightColor(), "" + active);
			}
		}
		boolean full = CommissionIntel.activeCount() >= PiratePatConfig.brokerMaxConcurrent();
		options.addOption("Ask about industrial equipment", OptionId.EQUIPMENT);
		options.addOption("Ask about blueprints", OptionId.BLUEPRINTS);
		if (full) {
			options.setEnabled(OptionId.EQUIPMENT, false);
			options.setEnabled(OptionId.BLUEPRINTS, false);
			options.setTooltip(OptionId.EQUIPMENT, "The network's hands are full with your existing business.");
			options.setTooltip(OptionId.BLUEPRINTS, "The network's hands are full with your existing business.");
		}
		options.addOption("That's all the business for now", OptionId.LEAVE);
	}

	protected void showCatalog(List<CatalogEntry> catalog, String pitch) {
		options.clearOptions();
		text.addPara(pitch);
		if (catalog.isEmpty()) {
			text.addPara("\"Nothing on the menu for you this month. Check back - inventory "
					+ "moves.\"");
		}
		if (beyondReach > 0) {
			text.addPara("Some of what the network handles is beyond this contact's reach - "
					+ "a better-placed contact could source more expensive goods.",
					Misc.getGrayColor(), "beyond this contact's reach");
		}
		float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
		for (CatalogEntry entry : catalog) {
			String label = entry.name;
			if (entry.source != null) label += " (" + entry.source.getName() + ")";
			options.addOption(label + " - " + Misc.getDGSCredits(entry.price), entry);
			if (entry.source != null && !entry.feasible) {
				options.setEnabled(entry, false);
				options.setTooltip(entry, "The network isn't strong enough to crack "
						+ entry.source.getName() + "'s defenses. A better-funded underworld "
						+ "could be.");
			} else if (entry.price > credits) {
				options.setEnabled(entry, false);
				options.setTooltip(entry, "You can't cover the deposit.");
			}
		}
		options.addOption("Ask about something else", OptionId.BACK);
		options.addOption("That's all the business for now", OptionId.LEAVE);
	}

	protected void showConfirm(CatalogEntry entry) {
		selected = entry;
		options.clearOptions();
		if (entry.source != null) {
			text.addPara("\"" + entry.name + ". It lives at " + entry.source.getName()
					+ ", in the " + entry.source.getStarSystem().getNameWithLowercaseType()
					+ ". The defenses have been looked at - the figure includes the danger "
					+ "premium.\" Your contact names it: %s, all of it up front.",
					Misc.getHighlightColor(), Misc.getDGSCredits(entry.price));
		} else {
			text.addPara("\"" + entry.name + ". It can be had.\" Your contact names the figure: "
					+ "%s, all of it up front.", Misc.getHighlightColor(),
					Misc.getDGSCredits(entry.price));
		}
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
				selected.price, selected.source);

		text.addPara("A code goes into a battered tripad and the money is gone. \"Pleasure. "
				+ "Watch your intel feed - my people will be in touch when there's something "
				+ "worth telling you.\"");
		options.clearOptions();
		options.addOption("That's all the business for now", OptionId.LEAVE);
	}

	// --- shared helpers (also used by CommissionIntel) ---

	/**
	 * The strongest raid the network can currently mount: the best
	 * commission-capable base's tier-determined raid strength. With zero
	 * bases, assume the tier-2 footing the war chest rebuilds at - the
	 * broker still deals during a rebuild, and weak targets stay orderable.
	 */
	public static float networkRaidStrength() {
		float best = 0f;
		PatronageBaseManager mgr = PatronageBaseManager.get();
		if (mgr != null) {
			for (PirateBaseIntel base : mgr.getBases()) {
				if (!(base instanceof PatronageBaseIntel) || base.isEnding() || base.isEnded()) continue;
				best = Math.max(best, base.getBaseRaidFP());
			}
		}
		if (best <= 0f) best = 150f; // tier-2 rebuild footing
		return best;
	}

	/**
	 * A colony's defense as the raid sim will see it: its faction's fleet
	 * strength in-system plus its station's strength - the same comparison
	 * PirateRaidActionStage makes when deciding whether a market can be
	 * raided at all.
	 */
	public static float defenseStrengthFor(MarketAPI market) {
		if (market.getStarSystem() == null) return Float.MAX_VALUE;
		float str = com.fs.starfarer.api.impl.campaign.command.WarSimScript.getFactionStrength(
				market.getFaction(), market.getStarSystem());
		str += com.fs.starfarer.api.impl.campaign.command.WarSimScript.getStationStrength(
				market.getFaction(), market.getStarSystem(), market.getPrimaryEntity());
		return str;
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

	// --- catalog construction ---

	/**
	 * The equipment menu is REAL: it lists items actually installed in NPC
	 * industries somewhere the raiders could go (no player systems, no
	 * pirate friends, no machine hives). The price carries a danger premium
	 * scaled to the source colony's war-sim defenses, and an entry the
	 * network isn't currently strong enough to crack shows greyed out - feed
	 * the war chest and the menu opens up. A successful commission takes
	 * the item off the colony's industry: theft, not manufacture.
	 */
	protected List<CatalogEntry> buildEquipmentCatalog() {
		int cap = priceCapFor(importance());
		beyondReach = 0;
		float networkStr = networkRaidStrength();
		float mult = PiratePatConfig.brokerPriceMult();
		float premiumPer100 = PiratePatConfig.brokerDefensePremiumPer100();

		java.util.Map<String, CatalogEntry> best = new java.util.LinkedHashMap<String, CatalogEntry>();
		for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
			if (market.isHidden() || market.isPlayerOwned()) continue;
			if (market.getFaction() == null || Misc.isPirateFaction(market.getFaction())) continue;
			if (UnderworldTithe.isOutsideUnderworldEconomy(market.getFaction())) continue;
			if (market.getStarSystem() == null) continue;
			// raids never enter systems with player colonies (vanilla rule)
			if (!Misc.getMarketsInLocation(market.getContainingLocation(),
					com.fs.starfarer.api.impl.campaign.ids.Factions.PLAYER).isEmpty()) continue;

			float defense = -1f;
			for (com.fs.starfarer.api.campaign.econ.Industry ind : market.getIndustries()) {
				SpecialItemData item = ind.getSpecialItem();
				if (item == null || item.getId() == null) continue;
				SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(item.getId());
				if (spec == null) continue;

				if (defense < 0f) defense = defenseStrengthFor(market);
				boolean feasible = networkStr >= defense;
				int price = (int) (spec.getBasePrice() * mult
						* (1f + defense / 100f * premiumPer100));
				if (price <= 0) continue;

				// one entry per item type: prefer a source the network can
				// actually crack, then the cheapest
				CatalogEntry prev = best.get(item.getId());
				boolean better = prev == null
						|| (feasible && !prev.feasible)
						|| (feasible == prev.feasible && price < prev.price);
				if (!better) continue;

				CatalogEntry entry = new CatalogEntry(item.getId(), item.getData(),
						spec.getName(), price);
				entry.source = market;
				entry.defense = defense;
				entry.feasible = feasible;
				best.put(item.getId(), entry);
			}
		}

		List<CatalogEntry> catalog = new ArrayList<CatalogEntry>();
		for (CatalogEntry entry : best.values()) {
			if (entry.price > cap) {
				beyondReach++;
				continue;
			}
			catalog.add(entry);
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

		int cap = priceCapFor(importance());
		beyondReach = 0;
		// don't offer a blueprint the player already has an order out for
		java.util.Set<String> inFlight = CommissionIntel.activeItemKeys();
		for (ShipHullSpecAPI spec : Global.getSettings().getAllShipHullSpecs()) {
			if (!spec.hasTag(Items.TAG_RARE_BP)) continue;
			// fighter hulls are LPCs, not ship blueprints
			if (spec.getHullSize() == HullSize.FIGHTER) continue;
			if (Global.getSector().getPlayerFaction().knowsShip(spec.getHullId())) continue;
			if (inFlight.contains(CommissionIntel.itemKey(CommissionIntel.SHIP_BP, spec.getHullId()))) continue;
			int price = (int) (priceForSpecial(CommissionIntel.SHIP_BP, spec.getHullId())
					* PiratePatConfig.brokerPriceMult());
			if (price <= 0) continue;
			if (price > cap) {
				beyondReach++;
				continue;
			}
			picker.add(new CatalogEntry(CommissionIntel.SHIP_BP, spec.getHullId(),
					spec.getHullName() + " blueprint", price), 1f);
		}
		for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
			if (!spec.hasTag(Items.TAG_RARE_BP)) continue;
			if (Global.getSector().getPlayerFaction().knowsWeapon(spec.getWeaponId())) continue;
			if (inFlight.contains(CommissionIntel.itemKey(CommissionIntel.WEAPON_BP, spec.getWeaponId()))) continue;
			int price = (int) (priceForSpecial(CommissionIntel.WEAPON_BP, spec.getWeaponId())
					* PiratePatConfig.brokerPriceMult());
			if (price <= 0) continue;
			if (price > cap) {
				beyondReach++;
				continue;
			}
			picker.add(new CatalogEntry(CommissionIntel.WEAPON_BP, spec.getWeaponId(),
					spec.getWeaponName() + " blueprint", price), 1f);
		}

		// how many archives the network can reach at once scales with how
		// well-placed the contact is; the config value is the ceiling
		int count = Math.min(1 + importance().ordinal(), PiratePatConfig.brokerBpOffers());
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

	// --- InteractionDialogPlugin boilerplate ---

	public void advance(float amount) {
	}

	public void backFromEngagement(EngagementResultAPI battleResult) {
	}

	public Object getContext() {
		return null;
	}

	public Map<String, MemoryAPI> getMemoryMap() {
		return memoryMap;
	}

	public void optionMousedOver(String optionText, Object optionData) {
	}
}
