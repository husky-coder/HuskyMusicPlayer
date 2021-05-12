package com.husky.mp.karaoke;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * AudioTrack播放器（主要是针对播放流，对于static模式这里不考虑）
 */
public class AudioTrackPlayer {

    private static final String TAG = "AudioTrackPlayer";

    private int SAMPLE_RATE;        // 采样率，默认44100 Hz
    private int CHANNEL_COUNT;      // 声道数，默认双声道
    private int ENCODING_PCM_BIT;   // 采样深度，默认16位
    private int TRANSFER_MODE;      // 模式，默认流加载模式

    private int minBufferSize;  // 最小的缓冲区大小

    private AudioTrack audioTrack;  // AudioTrack对象

    public AudioTrackPlayer(AudioTrackPlayer.Builder builder) {
        this(builder.samoleRate, builder.channelCount, builder.encodingBit, builder.transferMode);
    }

    public AudioTrackPlayer(int samoleRate, int channelCount, int encodingBit, int transferMode) {

        this.SAMPLE_RATE = samoleRate;
        this.CHANNEL_COUNT = channelCount;
        this.ENCODING_PCM_BIT = encodingBit;
        this.TRANSFER_MODE = transferMode;

        minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_COUNT, ENCODING_PCM_BIT);  // 最小缓冲区大小

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(ENCODING_PCM_BIT)    // 采样深度
                            .setSampleRate(SAMPLE_RATE)   // 采样率
                            .setChannelMask(CHANNEL_COUNT == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_OUT_STEREO) // 声道
                            .build())
                    .setBufferSizeInBytes(minBufferSize)    // 设置最小缓冲区大小
                    .setTransferMode(TRANSFER_MODE)         // 流媒体的模式
                    .build();
        } else {
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,  // 音频媒体类型
                    SAMPLE_RATE,                 // 采样率
                    CHANNEL_COUNT == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO, // 声道
                    ENCODING_PCM_BIT,           // 采样深度
                    minBufferSize,              // 设置最小缓冲区大小
                    TRANSFER_MODE);             // 流媒体的模式
        }
        audioTrack.play();  // 可以放在初始化的时候就调用play方法开启
    }

    /**
     * 播放解码得到的PCM数据
     *
     * @param audioData
     */
    public void write(byte[] audioData) {
        Log.d(TAG, "audioTrack.getPlayState()-->" + audioTrack.getPlayState());
        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.write(audioData, 0, audioData.length);
        }
    }

    /**
     * 播放解码得到的PCM数据
     *
     * @param audioData
     * @param offsetInBytes
     * @param sizeInBytes
     */
    public void write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
        if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            audioTrack.write(audioData, offsetInBytes, sizeInBytes);
        }
    }

    public void pause() {
        /**
         * 暂停播放数据，尚未播放的数据不会被丢弃，再次调用 play 时将继续播放。
         */
        audioTrack.pause();
    }

    public void resume() {
        audioTrack.play();
    }

    /**
     * 停止
     */
    public void stop() {
        /**
         * 停止播放音频数据
         * 如果是STREAM模式，会等播放完最后写入buffer的数据才会停止。
         * 如果立即停止，要调用pause()方法，然后调用flush方法，会舍弃还没有播放的数据。
         */
//        audioTrack.stop();  // 停止audioTrack

        audioTrack.pause();
        /**
         * 刷新当前排队等待播放的数据，已写入当未播放的数据将被丢弃，缓冲区将被清理。
         */
        audioTrack.flush(); // flush()只在模式为STREAM下可用
    }

    /**
     * 释放资源
     */
    public void release() {
        audioTrack.release();   // 释放AudioTrack
        audioTrack = null;  // 置空
    }

    public static class Builder {
        private int samoleRate = 44100;     // 采样率
        private int channelCount = 2;       // 声道数
        private int encodingBit = AudioFormat.ENCODING_PCM_16BIT;   // 采样深度
        private int transferMode = AudioTrack.MODE_STREAM;          // 数据加载模式

        public AudioTrackPlayer.Builder setSamoleRate(int samoleRate) {
            this.samoleRate = samoleRate;
            return this;
        }

        public AudioTrackPlayer.Builder setChannelCount(int channelCount) {
            this.channelCount = channelCount;
            return this;
        }

        public AudioTrackPlayer.Builder setEncodingBit(int encodingBit) {
            this.encodingBit = encodingBit;
            return this;
        }

        public AudioTrackPlayer.Builder setTransferMode(int transferMode) {
            this.transferMode = transferMode;
            return this;
        }

        public AudioTrackPlayer build() {
            return new AudioTrackPlayer(this);
        }
    }
}
