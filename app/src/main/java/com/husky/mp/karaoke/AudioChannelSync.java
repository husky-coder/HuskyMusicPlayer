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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 音频解码（同步）
 */
public class AudioChannelSync {

    private static final String TAG = "AudioChannelSync";

    private KaraokeManager karaokeManager;
    private MediaExtractor mediaExtractor;  // 解复用器
    private MediaCodec audioDecoder;    // 音频解码器

    private MediaCodec.BufferInfo bufferInfo;   // 保存输出缓冲区byteBuffer相关信息

    private ArrayBlockingQueue<byte[]> audioData;   // 缓存解码好的PCM数据

    private HandlerThread audioDecoderThread;
    private Handler audioDecoderHandler;

    // api 19 以上使用，通过遍历获取一个个ByteBuffer，api 21 以后提供根据下标直接拿到ByteBuffer的api
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;

    private volatile boolean decodeOver = false; // 是否解码结束，默认结束
    private boolean writePCM = false;   // 是否写PCM文件（供测试用）
    private FileOutputStream fos;   // 写PCM输出流（供测试用）

    public AudioChannelSync(KaraokeManager karaokeManager, MediaExtractor mediaExtractor) {
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
                mediaExtractor.selectTrack(i);
                try {
                    audioDecoder = MediaCodec.createDecoderByType(mime);   // 第一种方式

//                    // 第二种方式
//                    MediaCodecInfo mediaCodecInfo = getSupportCodec(mime);
//                    if (mediaCodecInfo == null) return;
//                    audioDecoder = MediaCodec.createByCodecName(mediaCodecInfo.getName());

                    audioDecoder.configure(mediaFormat, null, null, 0);
                    audioDecoder.start();   // 启动解码器

                    bufferInfo = new MediaCodec.BufferInfo();   // 保存返回输出缓冲区数据信息
                    // api 19 使用，api 21以后用另外方式
                    inputBuffers = audioDecoder.getInputBuffers();
                    outputBuffers = audioDecoder.getOutputBuffers();
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
        audioDecoderHandler.post(new AudioDecodeRunnable());
    }

    public void pause() {
        Log.d(TAG, audioDecoder + ">>pause");
//        audioDecoderHandler.post(new Runnable() {
//            @Override
//            public void run() {
//                Log.d(TAG, audioDecoder + ">>park");
//                LockSupport.park(); // 临时处理暂停，阻塞挂起线程
//            }
//        });
    }

    public void resume() {
        Log.d(TAG, audioDecoder + ">>resume");
//        LockSupport.unpark(audioDecoderThread); // 临时处理线程唤醒
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
        decodeOver = true;  // 通过标志位结束解码线程
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

    // 解码线程
    class AudioDecodeRunnable implements Runnable {

        @Override
        public void run() {
            AudioDecode();
        }
    }

    // 解码具体实现方法
    private void AudioDecode() {
        while (!isDecodeOver()) {
            try {
                Log.d(TAG, audioDecoder + ">>dequeueInputBuffer-->");
                int inputBufferIndex = audioDecoder.dequeueInputBuffer(10 * 1_000);
                if (inputBufferIndex > 0) {
                    ByteBuffer inputBuffer = null;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        inputBuffer = inputBuffers[inputBufferIndex];               // api < 21
                    } else {
                        inputBuffer = audioDecoder.getInputBuffer(inputBufferIndex); // api >= 21
                    }

                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        int bufferSize = mediaExtractor.readSampleData(inputBuffer, 0);
                        if (bufferSize < 0) {   // 无可用数据，证明读完了
                            audioDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decodeOver = true;
                            Log.d(TAG, audioDecoder + ">>queueInputBuffer decodeOver = " + isDecodeOver());
                            break;  // 跳出循环结束解码
                        } else {
                            audioDecoder.queueInputBuffer(inputBufferIndex, 0, bufferSize, mediaExtractor.getSampleTime(), mediaExtractor.getSampleFlags());
                            mediaExtractor.advance();   // 读取下一帧数据
                        }
                    }
                }

                Log.d(TAG, audioDecoder + ">>dequeueOutputBuffer-->");
                int outputBufferIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 10 * 1_000);
                while (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = null;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        outputBuffer = outputBuffers[outputBufferIndex];                // api < 21
                    } else {
                        outputBuffer = audioDecoder.getOutputBuffer(outputBufferIndex); // api >= 21
                    }

                    if (outputBuffer != null) {
                        byte[] PCMData = new byte[bufferInfo.size];
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

                    audioDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    outputBufferIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 10 * 1_000);
                }

                /**
                 * 前面获取待解码数据时已经处理了读到文件末尾时设置解码状态为结束并跳出循环
                 * 所以就不会还执行到这里的代码，也就无需再处理解码结束状态
                 */
//                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {   // 表示到达文件末尾了
//                    decodeOver = true;
//                    Log.d(TAG, audioDecoder + ">>dequeueOutputBuffer decodeOver = " + isDecodeOver());
//                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        // 解码结束时停止并重置解码器
        if (audioDecoder != null) {
            audioDecoder.stop();
            audioDecoder.reset();
        }
        audioData.clear();  // 清空缓冲队列
        Log.d(TAG, audioDecoder + ">>AudioDecode decodeOver = " + isDecodeOver());
    }
}
