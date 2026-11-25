package kr.allcheck.flutter.admin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFileRulesTest {
    private fun header(size: Long, bigEndian: Boolean = false): ByteArray {
        val header = ByteArray(12)
        (if (bigEndian) "RIFX" else "RIFF").toByteArray().copyInto(header)
        "WAVE".toByteArray().copyInto(header, 8)
        val declared = size - 8
        for (i in 0..3) header[if (bigEndian) 7 - i else 4 + i] = (declared shr (8 * i)).toByte()
        return header
    }
    @Test fun acceptsCompletedLittleAndBigEndianWave() {
        assertTrue(RecordingFileRules.closedWave(header(16000), 16000))
        assertTrue(RecordingFileRules.closedWave(header(16000, true), 16000))
    }
    @Test fun excludesStillGrowingOrPlaceholderHeader() {
        assertFalse(RecordingFileRules.closedWave(header(16000), 16001))
        assertFalse(RecordingFileRules.closedWave(header(8), 16000))
    }
    @Test fun rejectsEmptyTruncatedAndNonWaveFiles() {
        assertFalse(RecordingFileRules.closedWave(header(12), 12))
        assertFalse(RecordingFileRules.closedWave(ByteArray(4), 16000))
        assertFalse(RecordingFileRules.closedWave(ByteArray(12), 16000))
    }
}
