package com.smoothcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Records 10-bit HLG HDR video (HEVC Main10, BT.2020) plus AAC audio into an MP4.
 *
 * MediaRecorder can't tag the HDR color transfer, which leaves HDR video washed out, so this
 * follows Google's guidance and drives MediaCodec + MediaMuxer directly. The camera writes into a
 * persistent input surface, so each clip just attaches a new encoder to the same surface.
 *
 * Video timestamps come from the camera; the record output is configured with a MONOTONIC
 * timestamp base so they match System.nanoTime(), which we use for audio.
 */
class HdrRecorder(
    private val inputSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitRate: Int,
    private val output: FileDescriptor,
    private val withAudio: Boolean,
    private val orientationHint: Int,
) {
    companion object {
        private const val TAG = "HdrRecorder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_HEVC
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2

        fun videoFormat(width: Int, height: Int, fps: Int, bitRate: Int): MediaFormat =
            MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                // Without these the HDR video plays back washed out / clipped.
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }

        /**
         * Binds the persistent surface to an HDR encoder configuration once, so its buffer size and
         * format are known before the camera session is created. Throws if the encoder can't do it.
         */
        fun probe(surface: Surface, width: Int, height: Int, fps: Int, bitRate: Int) {
            val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
            try {
                codec.configure(videoFormat(width, height, fps, bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.setInputSurface(surface)
            } finally {
                codec.release()
            }
        }

        private fun nowUs() = System.nanoTime() / 1000
    }

    private val lock = Any()
    private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME)
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var sawKeyFrame = false
    private var wroteVideo = false
    private var lastAudioPts = -1L
    private var lastKeyRequestMs = 0L

    @Volatile private var stopping = false
    @Volatile private var stopDeadlineMs = Long.MAX_VALUE
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO before passing withAudio = true
    fun start() {
        muxer.setOrientationHint(orientationHint)
        videoCodec.configure(videoFormat(width, height, fps, bitRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoCodec.setInputSurface(inputSurface)

        if (withAudio) {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val ar = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 2, 32_768)
            )
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioRecord = ar
            audioCodec = ac
        }

        videoCodec.start()
        audioCodec?.start()
        audioRecord?.startRecording()
        videoThread = thread(name = "hdr-video") { safely { videoLoop() } }
        if (withAudio) audioThread = thread(name = "hdr-audio") { safely { audioLoop() } }
    }

    /** Stops and finalizes the file. Returns false if nothing usable was written. */
    fun stop(): Boolean {
        stopping = true
        stopDeadlineMs = System.currentTimeMillis() + 2_000
        try { videoCodec.signalEndOfInputStream() } catch (e: Exception) { Log.w(TAG, "signalEOS", e) }
        videoThread?.join(3_000)
        audioThread?.join(3_000)

        try { audioRecord?.stop() } catch (e: Exception) { /* ignore */ }
        audioRecord?.release()
        for (c in listOfNotNull(videoCodec, audioCodec)) {
            try { c.stop() } catch (e: Exception) { /* ignore */ }
            c.release()
        }

        var ok = false
        synchronized(lock) {
            if (muxerStarted) {
                try {
                    muxer.stop()
                    ok = wroteVideo
                } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop failed", e)
                }
            }
        }
        try { muxer.release() } catch (e: Exception) { /* ignore */ }
        return ok
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.e(TAG, "encoder thread failed", e) }
    }

    // Must hold lock.
    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0) return
        if (withAudio && audioTrack < 0) return
        muxer.start()
        muxerStarted = true
        requestKeyFrame()
    }

    private fun requestKeyFrame() {
        val now = System.currentTimeMillis()
        if (now - lastKeyRequestMs < 200) return
        lastKeyRequestMs = now
        try {
            videoCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) { /* ignore */ }
    }

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = videoCodec.dequeueOutputBuffer(info, 10_000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(lock) {
                    videoTrack = muxer.addTrack(videoCodec.outputFormat)
                    maybeStartMuxer()
                }
            } else if (idx >= 0) {
                val buf = videoCodec.getOutputBuffer(idx)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig && info.size > 0 && buf != null) {
                    synchronized(lock) {
                        if (muxerStarted) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) sawKeyFrame = true
                            if (sawKeyFrame) {
                                muxer.writeSampleData(videoTrack, buf, info)
                                wroteVideo = true
                            } else {
                                requestKeyFrame()
                            }
                        }
                    }
                }
                videoCodec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
            if (stopping && System.currentTimeMillis() > stopDeadlineMs) return
        }
    }

    private fun audioLoop() {
        val ar = audioRecord ?: return
        val ac = audioCodec ?: return
        val pcm = ByteArray(4_096) // 1024 stereo 16-bit frames
        val bytesPerFrame = CHANNELS * 2
        val info = MediaCodec.BufferInfo()
        var eosQueued = false

        while (true) {
            if (!eosQueued) {
                val inIdx = ac.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = ac.getInputBuffer(inIdx)
                    if (inBuf == null) {
                        ac.queueInputBuffer(inIdx, 0, 0, nowUs(), 0)
                    } else if (stopping) {
                        ac.queueInputBuffer(inIdx, 0, 0, nowUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    } else {
                        inBuf.clear()
                        val n = ar.read(pcm, 0, min(pcm.size, inBuf.remaining()))
                        val durUs = if (n > 0) (n / bytesPerFrame) * 1_000_000L / SAMPLE_RATE else 0L
                        val pts = nowUs() - durUs
                        if (n > 0) {
                            inBuf.put(pcm, 0, n)
                            ac.queueInputBuffer(inIdx, 0, n, pts, 0)
                        } else {
                            ac.queueInputBuffer(inIdx, 0, 0, pts, 0)
                        }
                    }
                }
            }

            while (true) {
                val outIdx = ac.dequeueOutputBuffer(info, if (eosQueued) 10_000 else 0)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    synchronized(lock) {
                        audioTrack = muxer.addTrack(ac.outputFormat)
                        maybeStartMuxer()
                    }
                } else if (outIdx >= 0) {
                    val b = ac.getOutputBuffer(outIdx)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && info.size > 0 && b != null) {
                        synchronized(lock) {
                            // Start audio together with the first video key frame; keep timestamps increasing.
                            if (muxerStarted && sawKeyFrame && info.presentationTimeUs > lastAudioPts) {
                                muxer.writeSampleData(audioTrack, b, info)
                                lastAudioPts = info.presentationTimeUs
                            }
                        }
                    }
                    ac.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                } else {
                    break // INFO_TRY_AGAIN_LATER
                }
            }
            if (stopping && System.currentTimeMillis() > stopDeadlineMs) return
        }
    }
}
