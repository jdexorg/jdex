package io.github.nitanmarcel.jdex.exec

class ExecLimits(
    val maxSteps: Int = 500_000,
    val maxMillis: Long = 2_000,
    val maxDepth: Int = 64,
)
