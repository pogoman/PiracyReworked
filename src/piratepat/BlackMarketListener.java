package piratepat;

import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.ShipSaleInfo;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.impl.items.BlueprintProviderItem;
import com.fs.starfarer.api.campaign.listeners.ColonyInteractionListener;
import com.fs.starfarer.api.impl.campaign.CoreCampaignPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.shared.PlayerTradeDataForSubmarket;

/**
 * Feeds the war chest from black market transactions. Trades count regardless
 * of transponder state - the authorities may not see you, but the pirates are
 * the counterparty. Values are actual demand-adjusted credits (vanilla's own
 * price computation), weighted by what the goods are worth to a war effort,
 * with recent opposing transactions netted out so wash-trading contributes
 * nothing.
 */
public class BlackMarketListener implements ColonyInteractionListener {

	public void reportPlayerOpenedMarket(MarketAPI market) {}
	public void reportPlayerClosedMarket(MarketAPI market) {}
	public void reportPlayerOpenedMarketAndCargoUpdated(MarketAPI market) {}

	public void reportPlayerMarketTransaction(PlayerMarketTransaction transaction) {
		if (!PiratePatConfig.enabled()) return;
		if (transaction == null || transaction.getSubmarket() == null) return;
		if (transaction.getSubmarket().getPlugin() == null) return;
		if (!transaction.getSubmarket().getPlugin().isBlackMarket()) return;

		MarketAPI market = transaction.getMarket();
		String submarketKey = market.getId() + "_" + transaction.getSubmarket().getSpecId();

		float total = 0f;

		for (CargoStackAPI stack : transaction.getSold().getStacksCopy()) {
			if (stack.getSize() <= 0) continue;
			String itemKey = stack.getType().name() + "_" + String.valueOf(stack.getData());
			float counted = PiratePatData.countSold(submarketKey, itemKey, stack.getSize());
			if (counted < 1) continue;

			float value = PlayerTradeDataForSubmarket.computePriceOfHavingAlreadySold(
					market, stack.getType(), stack.getData(), stack.getBaseValuePerUnit(), counted);
			total += value * weightFor(stack);

			if (stack.isSpecialStack() && stack.getPlugin() instanceof BlueprintProviderItem) {
				total += PiratePatConfig.blueprintBonus() * counted;
			}
		}

		for (CargoStackAPI stack : transaction.getBought().getStacksCopy()) {
			if (stack.getSize() <= 0) continue;
			String itemKey = stack.getType().name() + "_" + String.valueOf(stack.getData());
			float counted = PiratePatData.countBought(submarketKey, itemKey, stack.getSize());
			if (counted < 1) continue;

			float value = PlayerTradeDataForSubmarket.computePriceOfHavingAlreadyBought(
					market, stack.getType(), stack.getData(), stack.getBaseValuePerUnit(), counted);
			total += value * PiratePatConfig.buyWeight();
		}

		for (ShipSaleInfo info : transaction.getShipsSold()) {
			String hullId = info.getMember().getVariant().getHullSpec().getHullId();
			float counted = PiratePatData.countSold(submarketKey, "SHIP_" + hullId, 1f);
			if (counted <= 0) continue;
			total += info.getPrice() * counted * PiratePatConfig.shipWeight();
		}

		for (ShipSaleInfo info : transaction.getShipsBought()) {
			String hullId = info.getMember().getVariant().getHullSpec().getHullId();
			float counted = PiratePatData.countBought(submarketKey, "SHIP_" + hullId, 1f);
			if (counted <= 0) continue;
			total += info.getPrice() * counted * PiratePatConfig.buyWeight();
		}

		if (total > 0) {
			PiratePatData.addPlayerContribution(total, market.getName());
			WarChestIntel.ensureAdded();
			accrueDebt(market, total);
		}
	}

	/**
	 * Moving cargo through the black market costs the market's owning faction
	 * the tariff it would have collected on the same goods at its own docks.
	 * That evaded duty - not the cargo's full value, which was never theirs -
	 * is what they book as a loss, and what a collection house in independent
	 * space buys up as a receivable in your name. Only to the
	 * extent their port authority actually suspects you, though: this uses
	 * vanilla's live smuggling suspicion (the black market tooltip's level),
	 * so transponder-off trading generates nothing and careful smugglers
	 * stay off the books. Below the floor nothing accrues; attribution
	 * scales to 100% at the "full" suspicion level. Nobody writes paper
	 * against their own patron, so the pirates are skipped, and you cannot
	 * hold a claim against yourself.
	 *
	 * <p>The debt itself is one consolidated balance owed to one holder -
	 * see {@link PirateDebt}. Smuggling through a second faction raises the
	 * same balance rather than opening a second claim, so this method never
	 * needs to know what came before.
	 */
	private static void accrueDebt(MarketAPI market, float amount) {
		if (!PiratePatConfig.debtEnabled()) return;
		if (market.getFaction() == null) return;
		if (market.getFaction().isPlayerFaction()) return;
		String factionId = market.getFactionId();
		if (Factions.PIRATES.equals(factionId)) return;

		// what the faction actually lost is not the cargo's value - the goods
		// were never theirs. It is the tariff they would have collected had
		// the same cargo crossed their docks legally. That is the receivable.
		amount *= PiratePatConfig.debtTariffFraction();
		if (amount <= 0) return;

		float suspicion = CoreCampaignPluginImpl.computeSmugglingSuspicionLevel(market);
		if (suspicion < PiratePatConfig.debtSuspicionFloor()) return;
		float full = Math.max(0.01f, PiratePatConfig.debtSuspicionFull());
		amount *= Math.min(1f, suspicion / full);
		if (amount <= 0) return;

		if (PirateDebt.accrue(factionId, amount)) {
			// the debt was opened by this run: one quiet ledger line for
			// provenance, so a balance never seems to appear from nowhere.
			// Later runs just raise it, silently - the written notice on day
			// fifteen is where the player is told it matters.
			PiratePatData.addLedger("A collection house in independent space buys up "
					+ market.getFaction().getDisplayNameWithArticle() + "'s losses at "
					+ market.getName(), 0f);
		}
	}

	private static float weightFor(CargoStackAPI stack) {
		if (stack.isWeaponStack() || stack.isFighterWingStack()) {
			return PiratePatConfig.weaponWeight();
		}
		if (stack.isCommodityStack()) {
			String id = stack.getCommodityId();
			if (Commodities.ORE.equals(id) || Commodities.RARE_ORE.equals(id)) {
				return PiratePatConfig.oreWeight();
			}
		}
		return PiratePatConfig.commodityWeight();
	}
}
