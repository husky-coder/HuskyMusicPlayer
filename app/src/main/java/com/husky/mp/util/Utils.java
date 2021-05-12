package com.husky.mp.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 公共工具类
 */
public class Utils {

    private static String TAG = "Utils";

    /**
     * 复制assets目录下的文件到sd卡根目录
     *
     * @param context
     * @param assetsFileName
     * @param sdcardFilePath
     */
    public static void copyFileToSdcard(final Context context, final String assetsFileName, final String sdcardFilePath) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File file = new File(sdcardFilePath);
                if (file.exists()) {
                    Log.d(TAG, "文件已经存在sd卡根目录！");
                    return;
                }

                InputStream is = null;
                FileOutputStream fos = null;
                try {
                    is = context.getAssets().open(assetsFileName);
                    fos = new FileOutputStream(sdcardFilePath);

                    byte[] buffer = new byte[1024];
                    int size = -1;
                    while ((size = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, size);
                    }
                    Log.d(TAG, "文件复制sd卡根目录成功！");
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }).start();
    }
}
