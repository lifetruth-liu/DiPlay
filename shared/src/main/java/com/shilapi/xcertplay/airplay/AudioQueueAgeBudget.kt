package com.shilapi.xcertplay.airplay

import kotlin.math.ceil

/** Queue residence budget for real-time RTP data, expressed as four packet durations. */
internal object AudioQueueAgeBudget {
    private const val DEFAULT_PACKET_DURATION_MILLIS = 20.0
    private const val PACKETS_TO_BUFFER = 4.0
    private const val MAX_UDP_AGE_MILLIS = 0xffff_ffffL

    fun maxAgeMillis(format: AudioFormat, framesPerPacket: Number?): Long {
        val packetDurationMillis = if (format.codec == AudioCodecKind.OPUS) {
            // CarPlay's Opus audio packets use a 20 ms frame, matching microphoneConfig().
            DEFAULT_PACKET_DURATION_MILLIS
        } else {
            val frames = framesPerPacket?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            if (frames != null && format.sampleRate > 0) {
                frames * 1000.0 / format.sampleRate
            } else {
                DEFAULT_PACKET_DURATION_MILLIS
            }
        }
        return ceil(packetDurationMillis * PACKETS_TO_BUFFER)
            .toLong()
            .coerceIn(1L, MAX_UDP_AGE_MILLIS)
    }
}
