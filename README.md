# Pirate Patronage

A standalone mod for Starsector 0.98a. The black market is pirate
infrastructure - and every credit you spend there is revenue for the
underworld. In vanilla, trading with the transponder off has literally zero
consequences; this mod doesn't punish you for smuggling, it makes the
pirates *spend your money*.

## How it works

**The war chest.** Every black market transaction - buys and sells, transponder
on or off (the authorities may not see you, but the pirates are the
counterparty) - feeds a sector-wide pirate war chest. Sales are valued at
actual demand-adjusted credits and weighted by usefulness to a war effort:

- Weapons and fighter LPCs: x3
- Ships: x2
- Commodities (incl. drugs/organs): x1
- Raw ores: x0.5
- Blueprints: flat bonus on top of the vanilla behavior (pirates learn them)

Wash-trading contributes nothing: opposing buys/sells of the same goods at the
same submarket are netted against each other. Dumping colony stockpiles
self-limits the same way it always did - crashed sell prices mean crashed
contributions.

**A closed-loop economy.** Operating bases earn passive income (tuned to
sustain their vanilla raid cadence, no more). Launching a raid *costs* the
chest credits per fleet point - a broke underworld doesn't sail. A successful
raid pays spoils back with interest; a repelled raid is a pure loss. When the
chest covers the cost of the next base (rising with each base already
operating), the pirates expand - up to a cap far above vanilla's 3.

**The strategic grammar:**
- *Smuggle* and the underworld grows - left unchecked, it snowballs.
- *Repel raids* and it stagnates at break-even, unable to expand.
- *Hunt bases* and it shrinks: each kill destroys the pirates' investment and
  their income. Kill them all while the chest is low and piracy goes dormant -
  and the only thing that can revive it is *your* black market money.

**Fading wariness (respite piercing).** Piracy Respite - vanilla's permanent
reward for defeating the pirate colony crisis - says pirates are *wary* of
attacking you. Wariness fades when the war chest overflows with your own
money: once your lifetime contribution and your share of the underworld's
income cross the configured thresholds (500k / 25% by default), chest-funded
bases resume preying on your colonies - the vanilla pirate activity condition
(accessibility/stability drain, ambient fleets), never raids. Your share
dilutes as the pirate economy earns its own money, so laying off the black
market eventually restores the respite. Adopted vanilla bases always honor
respite; only bases the chest purchased are this bold.

**A price on your head.** Each faction whose markets you smuggle through
tracks the value you fed the pirates there and posts a *personal* bounty on
your head equal to it - no faction rep consequences, because nothing can be
proven well enough for open hostilities. But only to the extent their port
authority actually *suspects* you: bounty accrual scales with the market's
vanilla smuggling suspicion level (the black market tooltip's readout), from
nothing below "minimal" up to full value at "high". Transponder-off trading
generates no suspicion - a careful smuggler funds the pirates without ever
making a wanted list; a brazen one gets hunted. Once a faction's bounty crosses the
activation threshold (50k default), independent bounty hunter fleets start
ambushing you, with strength scaled to the bounty (1 FP per 1,000 credits,
capped at 200 FP) - and past the cap, the bounty funds multiple concurrent
hunter fleets, per faction. Fighting hunters carries no rep impact either
way. Bounties fade slowly (2%/month default) if you stop adding to them.

**Scaling defenses.** Vanilla base stations get restored as base tier rises,
but their defenders never grow past 2 light + 1 medium patrol. Here, tier 3+
bases field additional medium/heavy patrols and bigger fleets - a tier 5 base
is a fortress with a real garrison.

**Kept vanilla:** raid strength/frequency per base, base tier progression,
station restoration, pirate activity penalties, the 6-18 month respawn freeze
after a base kill, the first-year raid grace period. Removed: vanilla's rule
that every destroyed base makes future bases spawn stronger.

**The intel screen** ("Pirate War Chest") is a balance sheet: chest, income,
operating bases, savings toward the next base, the recent ledger - and exactly
how much of the pirate war economy *you* bankrolled.

## Compatibility

- Save-compatible to add mid-campaign: existing raiding bases are adopted
  as-is; the chest seeds accounting for them.
- Bases from the colony-crisis system (player-related bases) are untouched, as
  are Hostile Activity, Kanta's Protection, and Piracy Respite.
- Configurable via LunaLib (optional); bundled settings.json is the fallback.

## Building

`compile.ps1` (requires a JDK on PATH or JAVA_HOME). Output: `jars/PiratePat.jar`.
