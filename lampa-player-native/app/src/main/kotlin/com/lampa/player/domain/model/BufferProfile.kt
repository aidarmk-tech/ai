package com.lampa.player.domain.model

enum class BufferProfileType { LOW, MEDIUM, HIGH }

data class BufferProfile(
    val type: BufferProfileType,
    val maxBufferLengthMs: Long,
    val maxMaxBufferLengthMs: Long,
    val maxBufferSizeBytes: Long,
    val backBufferLengthMs: Long,
) {
    companion object {
        val LOW = BufferProfile(
            type = BufferProfileType.LOW,
            maxBufferLengthMs = 20_000,
            maxMaxBufferLengthMs = 40_000,
            maxBufferSizeBytes = 30 * 1024 * 1024,
            backBufferLengthMs = 10_000,
        )
        val MEDIUM = BufferProfile(
            type = BufferProfileType.MEDIUM,
            maxBufferLengthMs = 30_000,
            maxMaxBufferLengthMs = 90_000,
            maxBufferSizeBytes = 60 * 1024 * 1024,
            backBufferLengthMs = 30_000,
        )
        val HIGH = BufferProfile(
            type = BufferProfileType.HIGH,
            maxBufferLengthMs = 60_000,
            maxMaxBufferLengthMs = 240_000,
            maxBufferSizeBytes = 120 * 1024 * 1024,
            backBufferLengthMs = 60_000,
        )

        fun fromType(type: BufferProfileType) = when (type) {
            BufferProfileType.LOW -> LOW
            BufferProfileType.MEDIUM -> MEDIUM
            BufferProfileType.HIGH -> HIGH
        }
    }
}
