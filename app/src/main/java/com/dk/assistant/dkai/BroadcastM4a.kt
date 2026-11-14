package com.dk.assistant.dkai

import java.io.File

/** PCM (16-bit mono) -> AAC .m4a (MediaCodec + MediaMuxer) - WhatsApp / Telegram पर आवाज़ की तरह चले */
object BroadcastM4a {
    fun encode(pcm: ByteArray, rate: Int, out: File) {
        val mime = android.media.MediaFormat.MIMETYPE_AUDIO_AAC
        val fmt = android.media.MediaFormat.createAudioFormat(mime, rate, 1).apply {
            setInteger(android.media.MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, 64_000); setInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = android.media.MediaCodec.createEncoderByType(mime)
        val mux = android.media.MediaMuxer(out.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false
        try {
            codec.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            var pos = 0; var inDone = false; var done = false; var guard = 0
            fun pts(bytes: Int) = bytes / 2L * 1_000_000L / rate
            while (!done && guard++ < 2_000_000) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!; buf.clear()
                        val n = minOf(buf.remaining(), pcm.size - pos) and 1.inv()
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts(pos), android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { buf.put(pcm, pos, n); codec.queueInputBuffer(i, 0, n, pts(pos), 0); pos += n }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = mux.addTrack(codec.outputFormat); mux.start(); started = true }
                else if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); mux.writeSampleData(track, ob, info) }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
            if (!done) throw IllegalStateException("AAC पूरा नहीं बना")
        } finally {
            try { codec.stop() } catch (_: Exception) {}; try { codec.release() } catch (_: Exception) {}
            try { if (started) mux.stop() } catch (_: Exception) {}; try { mux.release() } catch (_: Exception) {}
        }
    }
}
