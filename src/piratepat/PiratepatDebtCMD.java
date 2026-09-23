package piratepat;

import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.ai.ModularFleetAIAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.impl.campaign.ids.Sounds;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.EndConversation;
import com.fs.starfarer.api.impl.campaign.rulecmd.SetStoryOption;
import com.fs.starfarer.api.impl.campaign.rulecmd.SetStoryOption.StoryOptionParams;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;

/**
 * The debt collector conversation - the ONE place a debt can be paid
 * (author decision D4: there is no broker option, no market payment and no
 * intel button; everything resolves fleet to fleet). The other way out is
 * fought rather than talked: the officer rule, see wonFight below.
 *
 * <p>Commands, all dispatched off the first parameter:
 * <ul>
 * <li><b>hasDebt</b> - condition for the BeginFleetEncounter row. Asks
 *     PirateDebt.isLive(), which folds in both master switches.
 * <li><b>encounter</b> - script side of the same row. Propagates the
 *     no-reputation-impact flags across the whole non-player side of the
 *     battle. Not in the spec; see the javadoc on propagateNoRepImpact for
 *     why it has to run here and nowhere else.
 * <li><b>greet</b> - builds the whole conversation: text and options.
 * <li><b>pay</b> / <b>challenge</b> / <b>fight</b> / <b>leave</b> - the
 *     option handlers.
 * <li><b>wonFight</b> - the PiratepatCollectorBeaten defeat trigger, fired by
 *     FID.winningPath() after ANY battle the player won against a collector,
 *     destroyed or not. It applies the officer rule: every officered hull
 *     dead means the debt is closed here and narrated; anything less means
 *     the crew keeps the contract, and the trigger is re-armed for the next
 *     fight, because the FID consumes it once fired.
 * </ul>
 *
 * <p>Three rules that are easy to break and expensive to debug:
 *
 * <p><b>Option ids are Strings.</b> FleetInteractionDialogPluginImpl.
 * optionSelected casts any non-String option data to its own OptionId enum, so
 * an enum or an Integer id crashes the dialog the instant it is clicked.
 *
 * <p><b>greet clears the option panel itself.</b> FireBest.applyRule only calls
 * clearOptions() when the rule's own option collection is non-empty, and every
 * debt row deliberately has an empty options column (CSV options and Java
 * options would not share a sort order).
 *
 * <p><b>Memory is read through getEntityMemory(memoryMap), never through
 * MemKeys.LOCAL.</b> At BeginFleetEncounter there is no active person, so LOCAL
 * is the fleet; at OpenCommLink the fleet commander is the active person, so
 * LOCAL is the PERSON and a bare read would silently address the wrong memory.
 *
 * <p>This class holds no state and is never serialized - it is instantiated by
 * the rules engine per invocation.
 */
public class PiratepatDebtCMD extends BaseCommandPlugin {

	/** Pay the quoted figure, or as much of it as is on board and sign for the rest. */
	public static final String OPT_PAY = "piratepat_debtPay";

	/** Story-point challenge; only ever offered while PirateDebt.canChallenge(). */
	public static final String OPT_CHALLENGE = "piratepat_debtChallenge";

	/** Ends the conversation on the FID main state, with the collector hostile. */
	public static final String OPT_FIGHT = "piratepat_debtFight";

	/** Cut the link. Costs a duck: bigger collectors and a bigger lie next time. */
	public static final String OPT_LEAVE = "piratepat_debtLeave";

	/**
	 * The terminal "nothing more to say" exit, after a payment or a signature.
	 * Deliberately ours rather than vanilla's cutCommLinkNoText: vanilla's row
	 * ends with a plain EndConversation, and every exit in this conversation
	 * uses NO_CONTINUE (spec decision 4).
	 */
	public static final String OPT_DONE = "piratepat_debtDone";

	/**
	 * Vanilla's own bonus-XP key, value 1 in starsector-core settings.json. It
	 * is semantically exact - this IS a loan renegotiation - and reusing it
	 * sidesteps the unverified question of whether a mod's nested bonusXP
	 * object merges into or replaces the core map.
	 */
	public static final String BONUS_XP_KEY = "negotiateLoanRate";

	@Override
	public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params,
			Map<String, MemoryAPI> memoryMap) {
		if (params == null || params.isEmpty()) return false;
		String command = params.get(0).getString(memoryMap);
		if (command == null) return false;

		// pure condition - asked from the rules conditions column, no dialog needed
		if (command.equals("hasDebt")) {
			return PirateDebt.isLive();
		}

		if (dialog == null) return false;

		if (command.equals("encounter")) {
			return propagateNoRepImpact(dialog);
		}

		CampaignFleetAPI collector = collector(dialog);
		if (collector == null) return false;

		if (command.equals("greet")) return greet(dialog, memoryMap, collector);
		if (command.equals("pay")) return pay(dialog, memoryMap, collector);
		if (command.equals("challenge")) return challenge(dialog, memoryMap, collector);
		if (command.equals("fight")) return fight(dialog, memoryMap, collector);
		if (command.equals("leave")) return leave(dialog, memoryMap, collector);
		if (command.equals("wonFight")) return wonFight(dialog, memoryMap, collector);

		return false;
	}

	/**
	 * The fleet this conversation is about. Inside the PiratepatCollectorBeaten
	 * defeat trigger the FID re-points the interaction target at each defeated
	 * fleet in turn, so this is correct there too.
	 */
	protected static CampaignFleetAPI collector(InteractionDialogAPI dialog) {
		SectorEntityToken target = dialog.getInteractionTarget();
		if (target instanceof CampaignFleetAPI) return (CampaignFleetAPI) target;
		return null;
	}

	// ------------------------------------------------------------------
	// BeginFleetEncounter
	// ------------------------------------------------------------------

	/**
	 * Author decision D2 costs one extra step that the spawn side cannot take.
	 *
	 * <p>A pirate-held collector is a genuine PIRATES fleet, and the player of
	 * this mod is very often FRIENDLY or COOPERATIVE with the pirates. The
	 * collector itself carries Misc.makeNoRepImpact from spawn, which both
	 * suppresses vanilla's RepActions.COMBAT_NORMAL (whose unconditional
	 * ensureAtBest = RepLevel.HOSTILE would drop a COOPERATIVE patron to
	 * HOSTILE on a single win) and kills the "the Pirates are not currently
	 * hostile, are you sure?" confirmation, which is gated on the raw faction
	 * relationship and never on the memory flag.
	 *
	 * <p>The catch: FleetEncounterContext.isNoRepImpact and isLowRepImpact read
	 * those flags ONLY off battle.getPrimary(battle.getNonPlayerSide()). A
	 * friendly pirate patrol pulled onto the collector's side that happens to
	 * be bigger becomes primary, and both protections silently evaporate. The
	 * battle and its pull-ins already exist by the time BeginFleetEncounter
	 * fires, so this is the first and only moment the whole side can be
	 * flagged.
	 *
	 * <p>Pulled-in fleets get the flags DIALOG-SCOPED (expire 0f), because a
	 * permanent no-rep-impact flag on an unrelated patrol would be a standing
	 * exploit. The collector is skipped outright - it already carries the
	 * permanent pair under the same reason string, and re-setting them with a
	 * zero expiry would downgrade them to dialog-scoped and let the collector
	 * fall non-hostile the moment the encounter ended.
	 */
	protected static boolean propagateNoRepImpact(InteractionDialogAPI dialog) {
		if (!(dialog.getPlugin() instanceof FleetInteractionDialogPluginImpl)) return false;
		Object context = ((FleetInteractionDialogPluginImpl) dialog.getPlugin()).getContext();
		if (!(context instanceof FleetEncounterContext)) return false;

		BattleAPI battle = ((FleetEncounterContext) context).getBattle();
		if (battle == null) return false;
		List<CampaignFleetAPI> side = battle.getNonPlayerSide();
		if (side == null) return false;

		int flagged = 0;
		for (CampaignFleetAPI curr : side) {
			if (curr == null) continue;
			MemoryAPI mem = curr.getMemoryWithoutUpdate();
			// already protected permanently - that is the collector itself
			if (mem.getBoolean(MemFlags.MEMORY_KEY_NO_REP_IMPACT)) continue;
			Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_LOW_REP_IMPACT,
					PirateDebt.FLAG_REASON, true, 0f);
			Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_NO_REP_IMPACT,
					PirateDebt.FLAG_REASON, true, 0f);
			flagged++;
		}
		if (flagged > 0 && PiratePatConfig.debugLogging()) {
			PirateDebt.log.info("Debt collector encounter: shielded " + flagged
					+ " pulled-in fleet(s) from reputation fallout");
		}
		return true;
	}

	// ------------------------------------------------------------------
	// the greeting
	// ------------------------------------------------------------------

	/**
	 * Reads the balance at the player and builds the option list. Called from
	 * the OpenCommLink row, and again after the story-point challenge so the
	 * player can act on the corrected figure immediately.
	 */
	protected boolean greet(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			CampaignFleetAPI collector) {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		// our rows carry no CSV options, so FireBest.applyRule does not clear
		options.clearOptions();

		// The BeginFleetEncounter row screens both of these, but the FID always
		// offers "Open a comm link" again, so a player who settles here and then
		// hails the same crew back lands in greet with nothing left to discuss.
		MemoryAPI mem = getEntityMemory(memoryMap);
		if (mem.getBoolean(PirateDebt.SETTLED_KEY)) {
			text.addPara("\"We're done here, captain. The paperwork's away.\" Whatever else they "
					+ "think of you, they do not say it on an open channel.");
			options.addOption("Cut the comm link", OPT_DONE);
			return true;
		}
		if (!PirateDebt.hasDebt()) {
			text.addPara("\"...disregard. The account's closed at this end.\" A pause, and then "
					+ "the link goes down without another word.");
			options.addOption("Cut the comm link", OPT_DONE);
			return true;
		}

		float real = PirateDebt.principal();
		float quoted = PirateDebt.quotedBy(collector);
		float cash = playerCredits();

		if (!PirateDebt.isPirateHeld()) {
			greetCreditor(text, quoted, cash);
		} else if (PirateDebt.isAudited()) {
			greetPirateAudited(text, quoted);
		} else {
			greetPirateQuoting(text, quoted, real);
		}

		addOptions(dialog, collector, quoted, cash);
		return true;
	}

	/** The collection house: bored, procedural, and entirely honest about the figure. */
	protected void greetCreditor(TextPanelAPI text, float quoted, float cash) {
		text.addPara("\"Captain. Standing receivable in your name, originating with %s. Balance "
				+ "is %s. We're authorised to settle it here.\"",
				Misc.getHighlightColor(),
				new String[] { PirateDebt.originName(), credits(quoted) });

		if (PirateDebt.hasPlan() && PirateDebt.isEnforcing() && PirateDebt.missed() > 0) {
			text.addPara("\"You've missed %s. This is the part where we stop writing letters.\"",
					Misc.getHighlightColor(),
					new String[] { countInWords(PirateDebt.missed()) });
		}

		if (cash < quoted) {
			text.addPara("\"You have %s on you. Whatever you can't cover, you sign for.\"",
					Misc.getHighlightColor(),
					new String[] { plain(cash) });
		}
	}

	/**
	 * The pirates, quoting a number they arrived at by feel.
	 *
	 * <p>AUTHOR DECISION D3: the true balance is printed underneath, in
	 * highlight colour, ALWAYS. The challenge is never a gamble on whether you
	 * are being lied to - it is a clean "I caught you", which is what a story
	 * point should buy. Do not soften, hide or hedge this.
	 *
	 * <p>The same node also covers the rare honest-by-accident case: a
	 * collector that spawned while the house still held the paper carries no
	 * frozen markup, so it quotes the true figure and the two lines simply
	 * agree. Nothing needs saying about that - the player can see it.
	 */
	protected void greetPirateQuoting(TextPanelAPI text, float quoted, float real) {
		String words = spellThousands(quoted);
		String figure = words != null ? words + " thousand" : credits(quoted);

		text.addPara("\"So you're the one.\" Flat, unhurried - somebody reading off a slate. \"The "
				+ "house got tired of chasing you and sold us the paper. Cheap.\" A pause while a "
				+ "voice off-mic says a number. \"%s. That's what it says here.\"",
				Misc.getHighlightColor(),
				new String[] { figure });

		text.addPara("Your own books put the balance at " + credits(real) + ".",
				PirateDebt.trueBalanceColor());
	}

	/** After the audit. Permanent for the life of this debt - no more invented figures. */
	protected void greetPirateAudited(TextPanelAPI text, float quoted) {
		text.addPara("\"%s.\" No theatre this time. \"Word got round that you read the "
				+ "paperwork. That figure and we're square.\"",
				Misc.getHighlightColor(),
				new String[] { credits(quoted) });
	}

	// ------------------------------------------------------------------
	// the options
	// ------------------------------------------------------------------

	/**
	 * Pay, challenge, fight, run. Rebuilt from scratch after the challenge, so
	 * everything it reads must come off PirateDebt rather than off a value the
	 * caller computed earlier.
	 *
	 * <p>Whichever money option is offered is never disabled - no
	 * DoCanAffordCheck, no greying out. At zero credits it opens the
	 * installment plan on the whole figure, which is the entire point of the
	 * mechanic.
	 *
	 * <p>The fight option carries the officer rule in its tooltip. It is the
	 * one place the player is told, before committing, what a win has to look
	 * like - and, with debtSellOnHouseDefeat on, that beating the house's
	 * crew sells the paper rather than ending anything.
	 */
	protected void addOptions(final InteractionDialogAPI dialog, final CampaignFleetAPI collector,
			float quoted, float cash) {
		OptionPanelAPI options = dialog.getOptionPanel();

		if (cash >= quoted) {
			options.addOption("Pay " + credits(quoted), OPT_PAY);
		} else if (cash >= 1f) {
			options.addOption("Pay the " + plain(cash)
					+ " you're carrying and take terms on the rest", OPT_PAY);
		} else {
			options.addOption("Take terms - " + plain(projectedInstallment(quoted))
					+ " a month", OPT_PAY);
		}
		if (cash < quoted) {
			options.setTooltip(OPT_PAY, "The balance becomes a monthly repayment drawn "
					+ "against your accounts. Miss one and they add half the shortfall back.");
		}

		if (PirateDebt.canChallenge()) {
			addChallengeOption(dialog, collector);
		}

		options.addOption("\"Come and take it.\"", OPT_FIGHT);
		if (!PirateDebt.isPirateHeld() && PiratePatConfig.debtSellOnHouseDefeat()) {
			options.setTooltip(OPT_FIGHT, "Destroy every ship with an officer aboard - the "
					+ "captain's included - and the house has nobody left to collect. It will "
					+ "sell your paper to the pirates rather than write it off, and their crew "
					+ "is the one you would have to finish. Let any officer get away and this "
					+ "one finds you again.");
		} else {
			options.setTooltip(OPT_FIGHT, "Destroy every ship with an officer aboard - the "
					+ "captain's included - and there is nobody left to collect: the debt dies "
					+ "with them. Let any officer get away and they will find you again.");
		}

		options.addOption("Cut the comm link", OPT_LEAVE);
		if (PirateDebt.isPirateHeld()) {
			options.setTooltip(OPT_LEAVE, "They will not stop looking, and every link you cut "
					+ "adds to whatever figure they invent the next time they find you.");
		} else {
			options.setTooltip(OPT_LEAVE, "They will not stop looking, and the balance keeps "
					+ "compounding until they find you again.");
		}
	}

	/**
	 * The story-point challenge, wired in the one order that works.
	 *
	 * <p>Five things here are load-bearing. The option is added BEFORE set(),
	 * because set() decorates an existing option. The delegate extends
	 * SetStoryOption.BaseOptionStoryPointActionDelegate and never
	 * BaseStoryPointActionDelegate, whose getLogText is commented out and so
	 * cannot be overridden in an anonymous subclass. setEnabled is never called
	 * afterwards, because that would defeat the affordability gate set() itself
	 * applies. The sound is the full Sounds constant, because set() hands
	 * params.soundId straight to makeStoryOption unmapped. And there is
	 * deliberately no confirm() override: the option dispatches normally to the
	 * DialogOptionSelected row AFTER the point has been spent, so implementing
	 * both would audit the debt twice.
	 *
	 * <p>The delegate reads the figures off PirateDebt inside
	 * createDescription rather than closing over numbers computed here. The
	 * delegate is built when the option list is, but the popup is drawn
	 * whenever the player hovers or clicks, which can be after the balance has
	 * moved - and it keeps the anonymous class's only captured local down to
	 * the two finals Java 7 requires.
	 */
	protected void addChallengeOption(final InteractionDialogAPI dialog,
			final CampaignFleetAPI collector) {
		OptionPanelAPI options = dialog.getOptionPanel();
		options.addOption("\"That number is invented, and we both know it.\"", OPT_CHALLENGE);
		options.setTooltip(OPT_CHALLENGE, "Read the original schedule back at them and force the "
				+ "figure down to what you actually owe.");

		final StoryOptionParams params = new StoryOptionParams(
				OPT_CHALLENGE,
				PiratePatConfig.debtChallengeStoryPoints(),
				BONUS_XP_KEY,
				Sounds.STORY_POINT_SPEND_LEADERSHIP,
				"Called a pirate crew on an invented debt figure");

		SetStoryOption.set(dialog, params,
				new SetStoryOption.BaseOptionStoryPointActionDelegate(dialog, params) {
			@Override
			public String getTitle() {
				return null;
			}
			@Override
			public void createDescription(TooltipMakerAPI info) {
				info.setParaInsigniaLarge();
				info.addPara("They are quoting %s. Your own books say %s.", 0f,
						Misc.getHighlightColor(),
						new String[] { Misc.getDGSCredits(PirateDebt.quotedBy(collector)),
									   Misc.getDGSCredits(PirateDebt.principal()) });
				info.addSpacer(20f);
				addActionCostSection(info);
			}
		});
	}

	// ------------------------------------------------------------------
	// PAY
	// ------------------------------------------------------------------

	/**
	 * Settle, or sign. PirateDebt.agreeToPay takes the cash, opens the plan on
	 * the remainder and - when the balance clears - writes the ledger line,
	 * posts the green message and stands every live collector down, so none of
	 * that is repeated here.
	 *
	 * <p>THE CRUEL RULE lives inside agreeToPay: the remainder of the QUOTE
	 * becomes the new true principal, so taking terms on an unchallenged
	 * pirate figure makes the lie real. It is fair only because the true
	 * balance was on screen in highlight colour the whole time, the pay
	 * tooltip warns, and the gray line below says so outright.
	 */
	protected boolean pay(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			CampaignFleetAPI collector) {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();

		if (!PirateDebt.hasDebt()) {
			text.addPara("There is nothing left on the books to settle.");
			options.addOption("Cut the comm link", OPT_DONE);
			return true;
		}

		boolean wasInflated = PirateDebt.markupOn(collector) > 1f;
		float quoted = PirateDebt.quotedBy(collector);

		float remaining = PirateDebt.agreeToPay(quoted, text);

		if (remaining < 1f) {
			text.addPara("You transfer the balance. A moment of dead air, then: \"Logged. Closed. "
					+ "Don't do it again.\" The line drops. They are already turning away.");
		} else {
			text.addPara("\"Nobody ever has it.\" Somewhere on the other end a file opens. "
					+ "\"Terms, then. %s a month against %s. We'll know the day you miss one.\"",
					Misc.getHighlightColor(),
					new String[] { plain(PirateDebt.installment()), plain(remaining) });
			if (wasInflated) {
				text.addPara("The figure they read into the record is the one you just agreed to.",
						Misc.getGrayColor());
			}
			// the paper is on terms now: this crew's job is finished, whatever
			// is still outstanding. markSettled is the mod's ONE stand-down.
			PirateDebt.markSettled(collector);
		}

		options.addOption("Cut the comm link", OPT_DONE);
		return true;
	}

	// ------------------------------------------------------------------
	// CHALLENGE
	// ------------------------------------------------------------------

	/**
	 * The story point has already been spent by the time this runs - the engine
	 * takes it on confirmation and then dispatches the option normally.
	 *
	 * <p>PirateDebt.audit sets the permanent flag for this debt AND zeroes this
	 * fleet's frozen multiplier, so the option list rebuilt below quotes the
	 * corrected figure and the challenge option disappears on its own.
	 */
	protected boolean challenge(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			CampaignFleetAPI collector) {
		TextPanelAPI text = dialog.getTextPanel();
		OptionPanelAPI options = dialog.getOptionPanel();
		options.clearOptions();

		PirateDebt.audit(collector);
		float real = PirateDebt.principal();

		text.addPara("You read the original terms back at them. Origination date, tonnages, the "
				+ "interest schedule, the name of the fixer who wrote the paper. Halfway through, "
				+ "someone behind the pirate says something short and unhappy.");

		String words = spellThousands(real);
		if (words != null) {
			text.addPara("\"...%s, then.\" The swagger has gone out of it. \"%s.\"",
					Misc.getHighlightColor(),
					new String[] { words, Misc.ucFirst(words) });
		} else {
			text.addPara("\"...%s, then.\" The swagger has gone out of it. \"That, and not a "
					+ "credit over.\"",
					Misc.getHighlightColor(),
					new String[] { credits(real) });
		}

		text.addPara("Nobody is going to try that number on you again.",
				PirateDebt.trueBalanceColor());

		addOptions(dialog, collector, PirateDebt.quotedBy(collector), playerCredits());
		return true;
	}

	// ------------------------------------------------------------------
	// FIGHT
	// ------------------------------------------------------------------

	/**
	 * Ends the conversation on the FID main state, with Engage waiting.
	 *
	 * <p>The flags are re-asserted at expire -1f, NOT at the spec's 0f. The
	 * collector already carries the same flags under the same reason string
	 * from spawn, and Misc.setFlagWithReason overwrites the reason key wholesale
	 * - writing a zero expiry here would demote the permanent hostility to
	 * dialog-scoped, and the collector would quietly stop being hostile the
	 * moment the encounter ended. Standing a collector down is markSettled's
	 * job and nothing else's.
	 *
	 * <p>The AI caches its target, so the tactical module has to be told to
	 * reconsider - MakeOtherFleetHostile does exactly this.
	 *
	 * <p>The defeat trigger is NOT registered here. It goes on at spawn, or
	 * every player who cuts the link and engages from the fleet screen would
	 * silently never see the sale.
	 *
	 * <p>EndConversation NO_CONTINUE, never DO_NOT_FIRE: DO_NOT_FIRE skips
	 * reinit() entirely and leaves an optionless dead dialog. NO_CONTINUE
	 * re-fires BeginFleetEncounter, which the already-set $piratepat_debtHandled
	 * falsifies, so the player lands on the Engage screen exactly once whether
	 * they got here through this option or by cutting the link first.
	 */
	protected boolean fight(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			CampaignFleetAPI collector) {
		MemoryAPI mem = getEntityMemory(memoryMap);

		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_HOSTILE,
				PirateDebt.FLAG_REASON, true, -1f);
		Misc.makeHostileToFaction(collector, Factions.PLAYER, true, -1f);
		Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_AGGRESSIVE,
				PirateDebt.FLAG_REASON, true, -1f);
		mem.set(MemFlags.MEMORY_KEY_IGNORE_PLAYER_COMMS, true, 0f);

		if (collector.getAI() instanceof ModularFleetAIAPI) {
			ModularFleetAIAPI ai = (ModularFleetAIAPI) collector.getAI();
			if (ai.getTacticalModule() != null) {
				ai.getTacticalModule().forceTargetReEval();
			}
		}

		new EndConversation().execute(null, dialog, Misc.tokenize("NO_CONTINUE"), memoryMap);
		return true;
	}

	// ------------------------------------------------------------------
	// LEAVE
	// ------------------------------------------------------------------

	/**
	 * Running is allowed - a two-frigate player must have an out - and the
	 * same crew simply keeps hunting; the only price is a bigger lie the next
	 * time the pirates quote you. PirateDebt.recordDuck guards per fleet, so
	 * re-opening comms with the same crew and cutting again is free.
	 */
	protected boolean leave(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			CampaignFleetAPI collector) {
		dialog.getTextPanel().addPara("You cut the link without answering. The last thing through "
				+ "is somebody laughing.");
		PirateDebt.recordDuck(collector);
		new EndConversation().execute(null, dialog, Misc.tokenize("NO_CONTINUE"), memoryMap);
		return true;
	}

	// ------------------------------------------------------------------
	// the defeat trigger
	// ------------------------------------------------------------------

	/**
	 * The player won a battle against a collector. Fired from
	 * FID.winningPath(), which adds its own Continue option afterwards - this
	 * command must not touch the option panel or end the conversation.
	 *
	 * <p>THE OFFICER RULE, narrated. winningPath fires for every fleet on the
	 * losing side whether or not it was wiped out, and losses have already
	 * been applied to the fleet, so PirateDebt.officersRemaining reads the
	 * truth: zero means the debt closes here, in the dialog, with the
	 * reputation line printed into it; anything else means the crew keeps
	 * the contract and nothing changes.
	 *
	 * <p>The FID removes a defeat trigger from the fleet once it has fired
	 * (FleetInteractionDialogPluginImpl.winningPath: triggers.remove on
	 * success), so a partial win would otherwise be the last time this ever
	 * ran for that crew. It is re-armed here. The FID iterates a COPY of the
	 * list and removes one instance from the live one afterwards, so adding
	 * a copy during the fire leaves exactly one behind; the manager's
	 * maintenance tick normalises the list to one copy as well, in case any
	 * of that ever changes.
	 */
	protected boolean wonFight(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap,
			CampaignFleetAPI collector) {
		TextPanelAPI text = dialog.getTextPanel();
		MemoryAPI mem = collector.getMemoryWithoutUpdate();

		// isLive, not hasDebt: with the system switched off a collector that
		// has not been stood down yet is just a hostile fleet, and a win
		// against it must neither narrate nor close a dormant balance
		if (!PirateDebt.isLive()) return true;
		if (mem.getBoolean(PirateDebt.SETTLED_KEY)) return true;
		if (PirateDebt.isCounted(collector)) return true;

		int officers = PirateDebt.officersRemaining(collector);
		if (officers > 0) {
			narrateSurvivors(text, collector, officers);
			// a RAW add, not ensureDefeatTrigger: the FID is still holding the
			// copy that just fired and removes it after this returns, so the
			// list has to carry two for a moment to be left with one. The
			// manager's maintenance tick collapses any duplicate that survives.
			Misc.addDefeatTrigger(collector, DebtCollectorManager.DEFEAT_TRIGGER);
			return true;
		}

		boolean wasPirateHeld = PirateDebt.isPirateHeld();
		boolean sold = PirateDebt.reportCollectorEliminated(collector, text);

		if (sold) {
			text.addPara("Your comms officer sweeps the wreckage for a command frequency and "
					+ "finds nothing. Every officer on that contract is dead. Two days later a "
					+ "broadcast turns up on a channel you were not meant to be listening to: "
					+ "the house has cut its losses, and your paper has changed hands - sold at "
					+ "a discount, to people who do not keep books.");
			text.addPara("The balance has not moved. The people collecting it have.",
					PirateDebt.trueBalanceColor());
		} else if (wasPirateHeld) {
			text.addPara("They die badly and without ceremony, the captain with them. Nobody "
					+ "left alive out there is paid enough to care about a number on a slate, "
					+ "and nobody back home is going to send another crew to find out what "
					+ "happened to this one.");
			text.addPara("The paper burns with them. Nobody is coming to collect.",
					Misc.getPositiveHighlightColor());
		} else {
			text.addPara("Your comms officer sweeps the wreckage for a command frequency and "
					+ "finds nothing. Every officer on that contract is dead, and whoever bought "
					+ "your paper has just learned exactly what it costs to collect on it. No "
					+ "crew in independent space will take the job now.");
			text.addPara("The receivable in your name is written off. Nobody is coming to "
					+ "collect.", Misc.getPositiveHighlightColor());
		}
		return true;
	}

	/**
	 * A win that was not the win. Somebody with a rank is still alive, so the
	 * contract is still alive - the crew limps off, and it comes back.
	 */
	protected void narrateSurvivors(TextPanelAPI text, CampaignFleetAPI collector,
			int officers) {
		if (PirateDebt.commanderAboard(collector)) {
			text.addPara("Their line breaks and what is left of it runs, but the captain's "
					+ "ship is not among the wrecks. Somebody out there still holds your paper, "
					+ "and now they know exactly what you fly.");
		} else {
			text.addPara("The captain's ship goes up with the rest of the line, but not every "
					+ "officer was aboard it. Someone junior has the slate now, and a reason to "
					+ "take it personally.");
		}
		String count = officers == 1 ? "one officer" : countInWords(officers) + " officers";
		if (officers > 1 && countInWords(officers) == null) count = officers + " officers";
		text.addPara("A collector with any officer left alive keeps the contract - " + count
				+ " got away. They will find you again.", Misc.getGrayColor());
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	/** Credits on board, never negative. */
	protected static float playerCredits() {
		CampaignFleetAPI player = Global.getSector().getPlayerFleet();
		if (player == null || player.getCargo() == null) return 0f;
		float cash = player.getCargo().getCredits().get();
		return cash < 0f ? 0f : cash;
	}

	/**
	 * What the monthly bill WOULD be if terms were signed on this figure right
	 * now. PirateDebt.installment() answers for the current PRINCIPAL, which is
	 * not the balance the plan would open on - on an unchallenged pirate quote
	 * it is deliberately nowhere near it. This used to mirror the formula by
	 * hand; it delegates now, so the two can never drift.
	 */
	protected static float projectedInstallment(float balance) {
		return PirateDebt.installmentFor(balance);
	}

	/** "42,000 credits" - for figures spoken aloud, where the glyph reads badly. */
	protected static String credits(float amount) {
		return Misc.getWithDGS(Math.round(amount)) + " credits";
	}

	/** "42,000" - a bare figure, no unit. */
	protected static String plain(float amount) {
		return Misc.getWithDGS(Math.round(amount));
	}

	private static final String[] UNDER_TWENTY = {
		"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
		"ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
		"seventeen", "eighteen", "nineteen"
	};

	private static final String[] TENS = {
		"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
	};

	/**
	 * 0-999 in words, in the spoken register the dialog uses: "seventy-five",
	 * "a hundred and seventy-five", "two hundred". Returns null out of range.
	 */
	protected static String countInWords(int n) {
		if (n < 0 || n > 999) return null;
		if (n < 20) return UNDER_TWENTY[n];
		if (n < 100) {
			int tens = n / 10;
			int unit = n % 10;
			if (unit == 0) return TENS[tens];
			return TENS[tens] + "-" + UNDER_TWENTY[unit];
		}
		int hundreds = n / 100;
		int rest = n % 100;
		String out = hundreds == 1 ? "a hundred" : UNDER_TWENTY[hundreds] + " hundred";
		if (rest == 0) return out;
		return out + " and " + countInWords(rest);
	}

	/**
	 * "a hundred and seventy-five" for 175,000 - the count of thousands, spoken.
	 *
	 * <p>Inflated quotes are rounded to 5,000 or 25,000, so they land on a whole
	 * number of thousands and read out loud the way a person would say them.
	 * Anything else - an honest unrounded balance, a figure over a million -
	 * returns null and the caller falls back to digits.
	 */
	protected static String spellThousands(float amount) {
		if (amount < 1000f || amount >= 1000000f) return null;
		int rounded = Math.round(amount);
		if (rounded % 1000 != 0) return null;
		return countInWords(rounded / 1000);
	}
}
