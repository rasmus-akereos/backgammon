package dk.rlunde.backgammon.core

import kotlin.random.Random

interface DiceRoller {
    fun roll(): Dice
}

/** Deterministic, reproducible roller — the backbone of tests and the self-play harness. */
class SeededDiceRoller(seed: Long) : DiceRoller {
    private val random = Random(seed)
    override fun roll(): Dice = Dice(random.nextInt(1, 7), random.nextInt(1, 7))
}

/** System-random roller for real play. Included so the DiceRoller interface has a 2nd impl. */
class RandomDiceRoller : DiceRoller {
    override fun roll(): Dice = Dice(Random.nextInt(1, 7), Random.nextInt(1, 7))
}
