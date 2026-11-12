package com.safestitch.app

import com.safestitch.app.audio.AudioTranscriptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTranscriptionServiceTest {

    @Test
    fun modelTranscribe_isGemini35Transcribe() {
        assertEquals("gemini-3.5-transcribe", AudioTranscriptionService.MODEL_TRANSCRIBE)
    }

    @Test
    fun wrapPcm16MonoAsWav_writesValid44ByteRiffWaveHeader() {
        val pcm = AudioTranscriptionService.synthesizeTonePcm16(durationMs = 100, sampleRate = 16_000)
        val wav = AudioTranscriptionService.wrapPcm16MonoAsWav(pcm, sampleRate = 16_000)

        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(wav.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(wav.copyOfRange(36, 40), Charsets.US_ASCII))
    }

    @Test
    fun buildFallbackTranscription_returnsEquipmentContextTranscript() {
        val feTranscript = AudioTranscriptionService.buildFallbackTranscription("FE-101")
        assertTrue(feTranscript.contains("FE-101"))
        assertTrue(feTranscript.contains("pressure gauge"))

        val elTranscript = AudioTranscriptionService.buildFallbackTranscription("EL-204")
        assertTrue(elTranscript.contains("EL-204"))
        assertTrue(elTranscript.contains("insulating rubber mat"))
    }
}
