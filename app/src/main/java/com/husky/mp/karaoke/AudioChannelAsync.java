package com.husky.mp.karaoke;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * 音频解码（异步）
 * api >= 21 时才支持异步模式
 */
public class AudioChannelAsync {

    private static final String TAG = "AudioChannelAsync";

    private KaraokeManager karaokeManager;    // 解码管理器
    private MediaExtractor mediaExtractor;  // 解复用器
    private MediaCodec audioDecoder;    // 音频解码器

    private ArrayBlockingQueue<byte[]> audioData;   // 缓存解码好的PCM数据

    // api >= 23 时使用，主要是将解码放入子线程中
    private HandlerThread audioDecoderThread;
    private Handler audioDecoderHandler;

    private volatile boolean decodeOver = true; // 是否解码结束，默认结束
    private boolean writePCM = false;   // 是否写PCM文件（供测试用）
    private FileOutputStream fos;   // 写PCM输出流（供测试用）

    public AudioChannelAsync(KaraokeManager karaokeManager, MediaExtractor mediaExtractor) {
        this.karaokeManager = karaokeManager;
        this.mediaExtractor = mediaExtractor;

        this.audioData = new ArrayBlockingQueue<>(5, true); // 指定容量和是否公平锁

        this.audioDecoderThread = new HandlerThread("audioDecoderThread");
        this.audioDecoderThread.start();
        this.audioDecoderHandler = new Handler(audioDecoderThread.getLooper());

        initDecoder();
    }

    /**
     * 两种方式创建，建议第一种
     * createDecoderByType
     * createByCodecName
     */
    private void initDecoder() {
        for (int i = 0; i < mediaExtractor.getTrackCount(); i++) {
            MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);    // 获取媒体类型
            if (mime != null && mime.startsWith("audio")) { // 找到音频轨道
                mediaExtractor.selectTrack(i);  // 选择轨道
                try {
                    // 第一种方式
                    audioDecoder = MediaCodec.createDecoderByType(mime);

//                    // 第二种方式
//                    MediaCodecInfo mediaCodecInfo = getSupportCodec(mime);
//                    if (mediaCodecInfo == null) return;
//                    audioDecoder = MediaCodec.createByCodecName(mediaCodecInfo.getName());

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {   // api >= 23
                        audioDecoder.setCallback(callback, audioDecoderHandler);
                    } else {    // 21 =< api < 23
                        audioDecoder.setCallback(callback);
                    }
                    // 需要在 setCallback 之后，配置 configure
                    audioDecoder.configure(mediaFormat, null, null, 0);
                } catch (IOException e) {
                    e.printStackTrace();
                    // 配置解码器出现异常重置解码结束标志和重置解码器
                    decodeOver = true;
                    audioDecoder.reset();
                }
                break;
            }
        }
    }

    // 异步回调
    private MediaCodec.Callback callback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
            Log.d(TAG, audioDecoder + ">>onInputBufferAvailable-->");
            if (index > 0) {
                ByteBuffer inputBuffer = audioDecoder.getInputBuffer(index); // api >= 21
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    int bufferSize = mediaExtractor.readSampleData(inputBuffer, 0);
                    if (bufferSize < 0 || isDecodeOver()) {   // 无可用数据，证明读完了
                        audioDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        decodeOver = true;
                        Log.d(TAG, audioDecoder + ">>onInputBufferAvailable decodeOver = " + isDecodeOver());
                    } else {
                        audioDecoder.queueInputBuffer(index, 0, bufferSize, mediaExtractor.getSampleTime(), mediaExtractor.getSampleFlags());
                        mediaExtractor.advance();   // 读取下一帧数据
                    }
                }
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
            Log.d(TAG, audioDecoder + ">>onOutputBufferAvailable-->");
            if (index >= 0) {
                ByteBuffer outputBuffer = audioDecoder.getOutputBuffer(index); // api >= 21
                if (outputBuffer != null) {
                    byte[] PCMData = new byte[info.size];
                    outputBuffer.get(PCMData);
                    outputBuffer.clear();
                    // 对PCMData数据进行处理
                    try {
                        audioData.put(PCMData);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    if (writePCM) {
                        // 写入文件（供测试用）
                        try {
                            fos.write(PCMData);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                // 释放outputBufferId上的数据
                audioDecoder.releaseOutputBuffer(index, false);
            }
            /**
             * 输入解码已经读到文件末尾以后，输出解码还不能马上执行到此处，需要将输出解码队列中解码完才能执行到此处
             * 如果加上解码是否结束标识符判断能马上停止解码（isDecodeOver）
             */
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || isDecodeOver()) {   // 表示到达文件末尾了
                // 停止并重置解码器
                if (audioDecoder != null) {
                    audioDecoder.stop();
                    audioDecoder.reset();
                }
                audioData.clear();  // 清空缓冲队列
                Log.d(TAG, audioDecoder + ">>onOutputBufferAvailable decodeOver = " + isDecodeOver());
            }
            Log.d(TAG, audioDecoder + ">>decodeOver = " + isDecodeOver());
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.d(TAG, audioDecoder + ">>onError-->");
            // 解码器错误情况下重置解码结束标志和重置解码器
            decodeOver = true;
            audioDecoder.reset();
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
            Log.d(TAG, audioDecoder + ">>onOutputFormatChanged-->");
        }
    };

    /**
     * 写PCM文件路径（供测试用）
     *
     * @param pcmPath
     */
    public void setPCMPath(String pcmPath) {
        try {
            fos = new FileOutputStream(new File(pcmPath));
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.writePCM = true;
    }

    /**
     * 获取PCM缓冲队列
     *
     * @return
     */
    public ArrayBlockingQueue<byte[]> getPCMQueue() {
        return audioData;
    }

    /**
     * 获取缓存解码好的PCM数据
     *
     * @return
     * @throws InterruptedException
     */
    public byte[] getPCMData() throws InterruptedException {
        return audioData.take();
    }

    /**
     * 是否解码结束
     *
     * @return
     */
    public boolean isDecodeOver() {
        return decodeOver;
    }

    /**
     * 根据mimeType获取支持的编解码器信息
     *
     * @param mimeType
     * @return
     */
    private MediaCodecInfo getSupportCodec(String mimeType) {
        int codecCount = MediaCodecList.getCodecCount();
        for (int i = 0; i < codecCount; i++) {
            MediaCodecInfo mediaCodecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!mediaCodecInfo.isEncoder()) {
                continue;
            }
            String[] types = mediaCodecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return mediaCodecInfo;
                }
            }
        }
        return null;
    }

    // 开始
    public void start() {
        decodeOver = false;
        audioDecoder.start();   // 启动解码器
    }

    public void pause() {
        Log.d(TAG, audioDecoder + ">>pause");
        audioDecoderHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, audioDecoder + ">>park");
                LockSupport.park(); // 临时处理暂停，阻塞挂起线程
            }
        });
    }

    public void resume() {
        Log.d(TAG, audioDecoder + ">>resume");
        LockSupport.unpark(audioDecoderThread); // 临时处理线程唤醒
    }

    public void stop() {
        /**
         * 不需要强制停止解码器，通过退出解码线程并设置解码状态为结束
         * 否则强制停止解码器但解码线程还没跑完，调用了解码器api抛出异常java.lang.IllegalStateException
         */
//        if (audioDecoder != null) {
//            audioDecoder.stop();
//            audioDecoder.reset();
//        }

        /**
         * 停止线程有三种方式
         * 1、使用标志位
         * 2、interrupt中断 + 异常捕获处理
         * 3、stop
         * 前两种都可以正常的退出线程，使用interrupt的方式需要考虑阻塞和非阻塞两种情况
         * stop方法属于非正常停止，会出现不可预知的结果
         */
        decodeOver = true;
    }

    public void release() {
        if (audioDecoder != null) {
            audioDecoder.release();
            audioDecoder = null;
        }
        if (mediaExtractor != null) {
            mediaExtractor.release();
            mediaExtractor = null;
        }
        if (audioDecoderThread != null) {
            audioDecoderThread.quitSafely();
        }
    }
}