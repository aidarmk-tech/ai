package com.aidar.pumpradar.domain.model

import kotlinx.serialization.Serializable

/**
 * Одна точка секундной траектории цены после сигнала: смещение от опорного
 * момента и лучшие bid/ask на этот момент. Хранится сериализованным списком в
 * [com.aidar.pumpradar.data.local.SignalTrajectoryEntity], чтобы исход можно
 * было пересчитать с учётом временной последовательности и стороны стакана
 * (а не по одним MFE/MAE).
 */
@Serializable
data class TrajectoryPoint(
    val offsetMs: Long,   // смещение от старта трека (0 = момент сигнала)
    val bid: Double,
    val ask: Double
)
