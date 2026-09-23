# Piracy Reworked

A standalone mod for Starsector 0.98a. The black market is pirate
infrastructure - and every credit that moves through the underworld is revenue
for it. In vanilla, trading with the transponder off has literally zero
consequences, and pirates fade into irrelevance the moment you stop needing
the black market; this mod doesn't punish you for smuggling, it makes the
pirates *spend your money* - and gives a rich late-game captain reasons to
keep dealing with them.

## The war chest

Every black market transaction - buys and sells, transponder on or off (the
authorities may not see you, but the pirates are the counterparty) - feeds a
sector-wide pirate war chest. Sales are valued at actual demand-adjusted
credits and weighted by usefulness to a war effort:

- Weapons and fighter LPCs: x3
- Ships: x2
- Commodities (incl. drugs/organs): x1
- Raw ores: x0.5
- Blueprints: flat bonus on top of the vanilla behavior (pirates learn them)

Wash-trading contributes nothing: opposing buys/sells of the same goods at the
same submarket are netted against each other.

**A closed-loop economy.** Operating bases earn passive income (tuned to
sustain their vanilla raid cadence, no more). Launching a raid *costs* the
chest credits per fleet point - a broke underworld doesn't sail. A successful
raid pays spoils back with interest; a repelled raid is a pure loss.

**Bases are priced by tier** (tier 1 ~94k up to tier 5 ~614k at defaults),
and the pirates decide what to buy by circumstance: with their footing gone
they rebuild it - the best tier affordable while reserving enough for another
starter; established, they save toward a base one tier above their current
best. A flush chest chains builds every couple of weeks, up to a cap far
above vanilla's 3.

**The strategic grammar:**
- *Smuggle* and the underworld grows - left unchecked, it snowballs.
- *Repel raids* and it stagnates at break-even, unable to expand.
- *Hunt bases* and it shrinks: each kill destroys the pirates' investment and
  their income, and slows the tithe to a trickle.

## The underworld tithe

The underworld also collects a monthly cut of the *sector's* illegal trade -
the same commodity flows vanilla routes as smuggling fleets, counted at both
ends of every run (the smuggler pays protection at the origin, the fence
takes a cut at the destination). This is income that scales with the size of
the economy instead of your personal habits, so pirates stay financed late
game even if you never fence another crate. It is sector income, never your
contribution - it steadily *dilutes* your share of the underworld's books.

**Your colonies are clients whether you like it or not.** Your markets'
share of the tithe is shown as its own intel line - a big colony's drug and
organ demand is met by smuggling into it. Free ports (anyone's, yours
included) fence at a multiplier: a free port is where everyone else's
contraband becomes legal cargo, so Free Port finally has an underworld price
tag.

With zero operating bases only a configurable trickle flows - eradication
buys years of quiet, but piracy follows interstellar civilization, and the
trickle eventually re-founds a base.

## The broker

Underworld-tagged **contacts** offer, alongside their regular job list, to
have things *sourced*: goods no legal market sells.

- **Industrial equipment - real theft.** The menu lists colony items
  actually installed at NPC colonies, scanned live from the economy, each
  entry naming its source. The price carries a danger premium scaled to the
  source colony's real defenses, and a source the network's best base can't
  crack shows greyed out - feed the war chest and the menu opens up. On
  success the item is *removed from the victim colony's industry*: theft
  with permanent consequences, not manufacture.
- **Blueprints.** A small rotating monthly menu of rare blueprints
  "liberated" from faction archives - copies, and of course the pirates keep
  one for themselves.

Terms: full deposit up front, straight into the war chest (it counts as your
personal contribution at full weight). Fulfillment is a **real raid** by an
operating war-chest base against the system holding the goods - resolved by
the actual raid sim against the target's actual defenses. Repelled raid:
half back. The broker deals even when the underworld has no bases - the
deposit then quite literally finances the reconstruction that will fulfill
it. Delivery lands in storage at the market where you ordered.

Access is gated the way vanilla gates contact missions: the option appears
at **Favorable** rep with the contact, and what their network can reach
scales with the contact's **importance** - the crown jewels (pristine
nanoforge, synchrotron) need a well-placed contact.

**Vanilla underworld jobs count too.** Money handed to underworld figures
through vanilla contact missions - custom production markups, hand-me-down
freighters - feeds the chest and your contribution the same way.

## Fading wariness (respite piercing)

Piracy Respite - vanilla's permanent reward for defeating the pirate colony
crisis - says pirates are *wary* of attacking you. Wariness fades when the
war chest overflows with your own money: once your lifetime contribution and
your share of the underworld's income cross the configured thresholds
(500k / 25% by default), chest-funded bases resume preying on your colonies -
the vanilla pirate activity condition (accessibility/stability drain,
ambient fleets), never raids. Your share dilutes as the underworld earns its
own money (tithe included), so laying off eventually restores the respite.
Hunting pirates also offsets your ledger: destroyed bases and fleets reduce
your lifetime contribution.

## The debt

**Off by default.** Switch it on in the LunaLib settings menu, or set
`piratepat_debtEnabled` in `data/config/settings.json`. It's safe to flip
either way mid-campaign: switched off, every collector stands down and goes
home, nothing accrues, nothing is billed, and any balance already on the books
simply sleeps; switched back on, that balance wakes with its notice and grace
periods restarted from that day.

Smuggling through a faction's black market doesn't put a price on your head -
it puts you in a ledger. What the faction lost isn't the cargo - that was
never theirs - it's the 30% tariff it would have collected had the same goods
crossed its docks legally. That evaded duty becomes a *receivable* in your
name, and a collection house in independent space buys it up at a discount
and comes to collect. Still no faction rep consequences (nothing can be
proven well enough for open hostilities) - just a balance, and people who
want it. Accrual is gated on the market's vanilla smuggling suspicion: none
below 0.05, scaling to full attribution at 0.3, so quiet transponder-off
trading generates nothing and brazen trading gets you billed. Commissioning a
broker raid adds 75% of the deposit - that one is theft, not evasion, and a
raid is loud. However many factions
you rob there is one balance and one holder; the house consolidates.

It compounds monthly - 3% while the house holds the paper (~43%/yr), 6% once
the pirates do (~101%/yr, doubling annually). A written notice arrives on day
15, nothing is dispatched before day 30 or under a 15,000 balance, and an
outstanding balance touches nothing else: no prices, no rep, no market access.
It just sits there getting bigger until a collector finds you.

**One collector, sized off your fleet, not off the money.** There is only
ever one collector fleet, and it is sent at full strength from the first day:
your *combat* fleet points - mothballed hulls, fighters and civilian ships
don't count - times 1.2. No ladder of smaller fleets, no escalation, no second
crew while the first is alive. The floor is clamped to your own fleet, so a
two-frigate captain can never be sent something bigger than they are, and no
collector fields a hull class more than one step above your biggest warship.
400 FP hard cap, 45 days of hunting; a crew that gives up, or dies to a
patrol, is replaced on the next roll.

**There is no pay-off button anywhere** - not on the intel screen, not at a
market, not through the broker. The debt is settled fleet-to-fleet, in the
interaction dialog, when a collector catches you:

- **Pay.** Cover it and you're square; cover part of it and you sign for the
  rest.
- **Take terms**, always offered, even at zero credits: the balance becomes a
  monthly repayment of that month's interest plus 20% of the principal (3,000
  floor), drawn through the vanilla monthly report and done in about eleven
  months. Collectors stop coming entirely while a plan runs. Miss one and half
  the shortfall is added back; miss two and the creditors start enforcing;
  miss three and the terms are torn up and the paper is sold on - and the
  collectors come straight back, at roughly double the rate, flying pirate
  colours. Signing is relief, not an exit.
- **Fight.** The contract lives in the crew's officers - see below.
- **Cut the link** and run. Allowed, because a small fleet needs an out, and
  the same crew simply keeps hunting. The only price is 15% per duck on
  whatever the pirates eventually claim you owe.

**Lose the fight and they collect by force.** Boarding parties take credits
first, then cargo by value - credited at half base value, since a hold of
goods is worth less to a collector than cash - until the balance is covered,
and the balance drops by exactly what they took. They never take people, AI
cores, or your last 100 supplies and fuel: caught broke in deep space is a
setback, not a soft-lock.

**Destroy every officer and the debt dies with them.** The one fleet's
contract lives in its officers: the captain's flagship and every hull with an
officer aboard. Kill all of them - however many fights it takes - and there is
nobody left to collect: the balance is written off, whoever held it. Let any
officer get away, or run yourself, and the same crew finds you again; nothing
is written up for the attempt. Finishing the house's crew *earns* pirate
reputation; once the pirates own your paper, the crew you finish is theirs,
and it costs. (Optional, off by default: `debtSellOnHouseDefeat` makes the
house sell your paper to the pirates when its crew dies instead of writing it
off, so the pirate crew is the one you have to finish.)

**Sold paper means real pirates** - genuine pirate fleets in pirate colours,
at 6%/month, quoting figures they invented: 1.3x to 2.2x the truth, rounded to
something suspiciously round. They come for you whatever your standing with
the pirates is, and being their patron does not exempt you from being their
debtor. Your real balance is printed beside their number every time, in
highlight, so calling them on it is never a gamble - it costs a story point,
and the audit is permanent for that debt.

*The cruel part:* the figure you agree to becomes the balance. Sign for an
inflated quote and the lie is what you owe from then on. The true number was
on screen the whole time.

## Everything else

- **Scaling defenses**: tier 3+ bases field additional medium/heavy patrols
  and bigger fleets, plus a tier-scaled garrison stationed on the base - a
  tier 5 base is a fortress.
- **Scaling bounties**: vanilla posts 40k-80k on a base by tier, a spread
  sized for defenses that never grow. Here the bounty grows with the fight:
  40/105/170/235/300k by tier at defaults (tier 5 pays what vanilla pays for
  a large Luddic Path base). Configurable, or switch it off for vanilla
  figures.
- **Machine factions excluded**: the Threat, Remnants, Omega and friends
  neither pay the tithe, nor get raided for commissions, nor extend credit.
- **Kept vanilla**: raid strength/frequency per base, tier progression,
  station restoration, the 6-18 month respawn freeze after a base kill, the
  first-year raid grace. Removed: vanilla's rule that every destroyed base
  makes future bases spawn stronger.
- **The intel screen** ("Pirate War Chest") is a balance sheet: chest,
  income streams (including the tithe and your colonies' share), operating
  bases, savings toward the next base, the recent ledger - and exactly how
  much of the pirate war economy *you* bankrolled. Your outstanding balance
  and who holds it are listed there too, read-only: it is a statement, not a
  payment screen.

## Compatibility

- Save-compatible to add mid-campaign: existing raiding bases are adopted
  as-is; the chest seeds accounting for them.
- Bases from the colony-crisis system (player-related bases) are untouched,
  as are Hostile Activity, Kanta's Protection, and Piracy Respite.
- Overrides the `cpc`/`hmdf` rows of `person_missions.csv` with subclasses
  (behavior unchanged, payments credited) - flag if another mod edits those.
- Configurable via LunaLib (optional); bundled settings.json is the fallback.

## Building

`compile.ps1` (requires a JDK on PATH or JAVA_HOME). Output: `jars/PiratePat.jar`.
