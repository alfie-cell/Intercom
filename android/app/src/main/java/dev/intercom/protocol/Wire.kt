package dev.intercom.protocol

/** Protocol constants. Normative reference: PROTOCOL.md at repo root. */
object Wire {
    const val MAGIC0: Byte = 0x49 // "I"
    const val MAGIC1: Byte = 0x43 // "C"
    const val VERSION: Byte = 1
    const val HEADER_BYTES = 12

    const val SERVICE_TYPE = "_intercom._udp"
    /** Preferred receiver port; falls back to an ephemeral one. The advertised port is authoritative. */
    const val PREFERRED_PORT = 47474

    /** Audio format id 1: PCM 24 kHz mono s16le, 20 ms frames. */
    const val FORMAT_PCM24K: Byte = 1
    const val SAMPLE_RATE = 24_000
    const val FRAME_SAMPLES = 480
    const val FRAME_BYTES = 960
    const val FRAME_MICROS = 20_000L

    const val START_END_RESENDS = 3
    const val RESEND_SPACING_MS = 20L
    const val TALK_TIMEOUT_MICROS = 1_000_000L
    const val MAX_TALK_MS = 60_000L
    const val DEFAULT_DELAY_MS = 250
    const val PING_COUNT = 5
    const val PING_SPACING_MS = 50L

    /** µs on the talk timeline → sample index at 24 kHz (exact: 24 000 / 1 000 000 = 3 / 125). */
    fun microsToSamples(micros: Long): Long = Math.floorDiv(micros * 3, 125L)
}
