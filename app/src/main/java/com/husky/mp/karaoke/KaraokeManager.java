package com.husky.mp.karaoke;

import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;

/**
 * 同步处理方式
 * <p>
 * 异步模式与同步模式的区别在于：
 * 　　》异步模式下通过回调函数来自动的传递可用的input buffer 或 output buffer
 * <p>
 * 　　》同步模式下需要通过dequeueInputBuffer(...)或dequeueOutputBuffer(...)来请求获取可用的input buffer 或 output buffer
 */
public class KaraokeManager {

    private static final String TAG = "KaraokeManager";

    private boolean isOriginal;  // 是否原唱
    private int originalVolume = 0;  // 原唱音量
    private int musicVolume = 100;    // 伴唱音量

    private String inputPath1;   // 媒体路径
    private String inputPath2;   // 媒体路径
    private MediaExtractor mediaExtractor1;  // 解复用器对象
    private MediaExtractor mediaExtractor2;  // 解复用器对象

    //        private AudioChannelSync audioChannel1;  // 音频流处理（同步）
//    private AudioChannelSync audioChannel2;  // 音频流处理（同步）
    private AudioChannelAsync audioChannel1;  // 音频流处理（异步）
    private AudioChannelAsync audioChannel2;  // 音频流处理（异步）

    private OnPrepareListener onPrepareListener;    // 准备就绪监听回调接口

    private Handler mainHandler;    // 主线程handler

    private AudioTrackPlayer audioTrackPlayer;  // 音频播放器

    public KaraokeManager(String inputPath1, String inputPath2) {
        this.inputPath1 = inputPath1;
        this.inputPath2 = inputPath2;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置写入PCM的路径（供测试用）
     *
     * @param original
     * @param music
     */
    public void setPCMPath(String original, String music) {
        audioChannel1.setPCMPath(original);
        audioChannel2.setPCMPath(music);
    }

    /**
     * 准备
     */
    public void prepare() {
        // TODO 需要处理已经开启播放情况下不再开启

        if (TextUtils.isEmpty(inputPath1) || TextUtils.isEmpty(inputPath2)) {
            throw new NullPointerException("media path must be not null!");
        }

        // 准备操作耗时，放在子线程执行
        new Thread(new Runnable() {
            @Override
            public void run() {
                /**
                 * 重置音量为默认值
                 */
                if (isOriginal) {
                    originalVolume = 100;  // 原唱音量
                    musicVolume = 0;    // 伴唱音量
                } else {
                    originalVolume = 0;  // 原唱音量
                    musicVolume = 100;    // 伴唱音量
                }

                try {
                    // 已经初始化过的需要释放
                    if (mediaExtractor1 != null) {
                        mediaExtractor1.release();
                    }
                    mediaExtractor1 = new MediaExtractor();
                    mediaExtractor1.setDataSource(inputPath1);

                    // 已经初始化过的需要释放
                    if (mediaExtractor2 != null) {
                        mediaExtractor2.release();
                    }
                    mediaExtractor2 = new MediaExtractor();
                    mediaExtractor2.setDataSource(inputPath2);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // TODO 目前解码器和播放器未处理重用，每次都重新创建解码器和播放器
//                audioChannel1 = new AudioChannelSync(KaraokeManager.this, mediaExtractor1);
//                audioChannel2 = new AudioChannelSync(KaraokeManager.this, mediaExtractor2);
                audioChannel1 = new AudioChannelAsync(KaraokeManager.this, mediaExtractor1);
                audioChannel2 = new AudioChannelAsync(KaraokeManager.this, mediaExtractor2);

                // TODO 动态获取并播放参数（采样率、声道），目前只处理原唱伴唱的采样率、声道、采样深度一致的情况
                MediaFormat mediaFormat = null;
                for (int i = 0; i < mediaExtractor1.getTrackCount(); i++) {
                    mediaFormat = mediaExtractor1.getTrackFormat(i);
                    String mime = mediaFormat.getString(MediaFormat.KEY_MIME);    // 获取媒体类型
                    if (mime != null && mime.startsWith("audio")) { // 找到音频轨道
                        Log.d(TAG, "audio.rate = " + mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                        Log.d(TAG, "audio.channel = " + mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        break;
                    }
                }

                // TODO 需要判断 mediaFormat 是否可用
                audioTrackPlayer = new AudioTrackPlayer(mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT), AudioFormat.ENCODING_PCM_16BIT, AudioTrack.MODE_STREAM);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // 主线程中回调
                        onPrepareListener.onPrepared();
                    }
                });
            }
        }).start();
    }

    /**
     * 开始
     */
    public void start() {
        audioChannel1.start();
        audioChannel2.start();
        // 启动合成播放线程
        new Thread(new AudioMergeRunnable()).start();
    }

    public void pause() {
        // 暂停解码
        audioChannel1.pause();
        audioChannel2.pause();
//        audioTrackPlayer.pause();   // 暂停播放
    }

    public void resume() {
        // 恢复解码
        audioChannel1.resume();
        audioChannel2.resume();
//        audioTrackPlayer.resume();   // 恢复播放
    }

    /**
     * 停止
     */
    public void stop() {
        audioChannel1.stop();
        audioChannel2.stop();
    }

    /**
     * 释放资源
     */
    public void release() {
        mediaExtractor1.release();
        mediaExtractor2.release();
        audioChannel1.release();
        audioChannel2.release();
        audioTrackPlayer.release();
        mainHandler.removeCallbacksAndMessages(null);   // 清空消息队列
    }

    /**
     * 切换原唱伴唱
     *
     * @param isOriginal
     */
    public void setOriginal(boolean isOriginal) {
        this.isOriginal = isOriginal;
    }

    // 混音播放线程
    class AudioMergeRunnable implements Runnable {

        @Override
        public void run() {
            while (true) {
                Log.d(TAG, "audioChannel1.isDecodeOver = " + audioChannel1.isDecodeOver());
                Log.d(TAG, "audioChannel1.getPCMQueue = " + audioChannel1.getPCMQueue().size());
                Log.d(TAG, "audioChannel2.isDecodeOver = " + audioChannel2.isDecodeOver());
                Log.d(TAG, "audioChannel2.getPCMQueue = " + audioChannel2.getPCMQueue().size());

                try {
                    byte[] original = null;
                    byte[] music = null;

                    if (!audioChannel1.isDecodeOver() || audioChannel1.getPCMQueue().size() != 0) {
                        original = audioChannel1.getPCMData();
                    }
                    if (!audioChannel2.isDecodeOver() || audioChannel2.getPCMQueue().size() != 0) {
                        music = audioChannel2.getPCMData();
                    }

                    if (original == null && music == null) {
                        break;
                    }

                    byte[] mix;
                    if (original != null && music != null) {
                        Log.d(TAG, "mix");
                        Log.d(TAG, "original.length = " + original.length);
                        Log.d(TAG, "music.length = " + music.length);

                        // 根据是否原唱修改音量
                        if (isOriginal) {
                            modifyOriginalVolume();
                        } else {
                            modifyMusicVolume();
                        }

                        int minLength = 0;  // 获取最小长度
                        if (original.length >= music.length) {
                            minLength = music.length;
                            mix = new byte[original.length];
                            System.arraycopy(original, 0, mix, 0, original.length);
                        } else {
                            minLength = original.length;
                            mix = new byte[music.length];
                            System.arraycopy(music, 0, mix, 0, music.length);
                        }

                        short temp1, temp2; // 从文件还原的声音点
                        int temp;   // 两个声音点相加后的值，有可能超过short（一个声音点的值），所以用int接收

                        /**
                         * 16 bit 采样深度，一个声音采样点是两个字节，也就是低八位在前面，高八位在后面
                         * 如果是双声道，则左声道的低八位高八位，右声道的低八位高八位。。。依次存储在文件上
                         * 所以这里 i += 2 是循环依次取两个字节也就是一个声音点，进行低高八位操作
                         * 较短的数组长度作为遍历结束条件，避免数组越界
                         */
                        for (int i = 0; i < minLength; i += 2) {
                            temp1 = (short) ((original[i] & 0xff) | (original[i + 1] & 0xff) << 8);
                            temp2 = (short) ((music[i] & 0xff) | (music[i + 1] & 0xff) << 8);
                            temp = (int) (temp1 * (originalVolume / 100f)) + (int) (temp2 * (musicVolume / 100f));
//                            temp = temp1 + temp2; // 单纯的混音，不考虑音量
                            // 相加后的声音点不能超过声音波形范围
                            if (temp > 32767) { // 波形最高点
                                temp = 32767;
                            } else if (temp < -32768) { // 波形最低点
                                temp = -32768;
                            }

                            mix[i] = (byte) (temp & 0xff);
                            mix[i + 1] = (byte) ((temp >>> 8) & 0xff);
                        }
                    } else if (original == null) {
                        Log.d(TAG, "music");
                        modifyMusicVolume();

                        mix = new byte[music.length];
                        System.arraycopy(music, 0, mix, 0, music.length);
                    } else {
                        Log.d(TAG, "original");
                        modifyOriginalVolume();

                        mix = new byte[original.length];
                        System.arraycopy(original, 0, mix, 0, original.length);
                    }

                    Log.d(TAG, "originalVolume = " + originalVolume);
                    Log.d(TAG, "musicVolume = " + musicVolume);

                    // 写入播放器进行播放
                    audioTrackPlayer.write(mix);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    break;
                }
            }
            // 停止播放器
            audioTrackPlayer.stop();
            Log.d(TAG, "audioTrackPlayer-->stop");
        }
    }

    /**
     * 修改原唱音量
     */
    private void modifyOriginalVolume() {
        if (originalVolume < 100)
            originalVolume = originalVolume + 2;
        if (musicVolume > 0)
            musicVolume = musicVolume - 2;
    }

    /**
     * 修改伴唱音量
     */
    private void modifyMusicVolume() {
        if (originalVolume > 0)
            originalVolume = originalVolume - 2;
        if (musicVolume < 100)
            musicVolume = musicVolume + 2;
    }

    /**
     * 设置准备就绪监听回调接口
     *
     * @param onPrepareListener
     */
    public void setOnPrepareListener(OnPrepareListener onPrepareListener) {
        this.onPrepareListener = onPrepareListener;
    }

    /**
     * 准备就绪接口
     */
    public interface OnPrepareListener {
        void onPrepared();
    }

    public static class Builder {

    }
}
