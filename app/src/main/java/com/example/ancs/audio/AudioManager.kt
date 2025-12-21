package com.example.ancs.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

class AudioManager {
    private val tag = "ANCS_Audio"
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    
    companion object {
        const val SAMPLE_RATE = 16000 // 16 kHz
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE = 4096
    }
    
    /**
     * Start capturing audio from microphone
     * @param onAudioFrame Callback when audio frame is captured
     */
    fun startCapture(onAudioFrame: (ByteArray) -> Unit) {
        if (isRecording) {
            Log.w(tag, "Already recording")
            return
        }
        
        thread {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                )
                
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
                )
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "AudioRecord not initialized")
                    return@thread
                }
                
                audioRecord?.startRecording()
                isRecording = true
                Log.d(tag, "Audio capture started (buffer: $bufferSize)")
                
                val buffer = ByteArray(CHUNK_SIZE)
                
                while (isRecording) {
                    try {
                        val bytesRead = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                        if (bytesRead > 0) {
                            // Send only the bytes read
                            onAudioFrame(buffer.copyOf(bytesRead))
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error reading audio", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in startCapture", e)
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                isRecording = false
                Log.d(tag, "Audio capture stopped")
            }
        }
    }
    
    /**
     * Stop capturing audio
     */
    fun stopCapture() {
        isRecording = false
        Log.d(tag, "Stopping audio capture")
    }
    
    /**
     * Start playing audio from received frames
     * @param onPlaybackReady Called when audio track is ready
     */
    fun startPlayback(onPlaybackReady: () -> Unit = {}) {
        if (isPlaying) {
            Log.w(tag, "Already playing")
            return
        }
        
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )
            
            audioTrack = AudioTrack(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AUDIO_FORMAT)
                    .build(),
                bufferSize * 2,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            
            audioTrack?.play()
            isPlaying = true
            Log.d(tag, "Audio playback started (buffer: $bufferSize)")
            onPlaybackReady()
        } catch (e: Exception) {
            Log.e(tag, "Error starting playback", e)
        }
    }
    
    /**
     * Play received audio frame
     * @param audioFrame Audio data in PCM16 format
     */
    fun playAudio(audioFrame: ByteArray) {
        if (!isPlaying || audioTrack == null) {
            Log.w(tag, "Playback not active")
            return
        }
        
        try {
            audioTrack?.write(audioFrame, 0, audioFrame.size)
        } catch (e: Exception) {
            Log.e(tag, "Error playing audio", e)
        }
    }
    
    /**
     * Stop playing audio
     */
    fun stopPlayback() {
        isPlaying = false
        thread {
            try {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                Log.d(tag, "Audio playback stopped")
            } catch (e: Exception) {
                Log.e(tag, "Error stopping playback", e)
            }
        }
    }
    
    /**
     * Release all audio resources
     */
    fun release() {
        stopCapture()
        stopPlayback()
    }
    
    fun isRecordingActive(): Boolean = isRecording
    fun isPlayingActive(): Boolean = isPlaying
}
