package piratepat;

import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction;
import com.fs.starfarer.api.campaign.PlayerMarketTransaction.ShipSaleInfo;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.impl.items.BlueprintProviderItem;
import com.fs.starfarer.api.campaign.listeners.ColonyInteractionListener;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
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
