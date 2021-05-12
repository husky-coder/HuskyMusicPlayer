package com.husky.mp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.husky.mp.karaoke.KaraokeManager;
import com.husky.mp.util.StorageUtil;
import com.husky.mp.util.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private KaraokeManager karaokeManager;
    private String mediaName1 = "8ecfbd564a060db52723e3aef8bfebb1.mp3";
    private String mediaName2 = "7593b72b74f4e6ed92c182ca259d60c5.mp3";
    private String decodPcmName1 = "decodPcm1.pcm";
    private String decodPcmName2 = "decodPcm2.pcm";
    private String mergePcm = "merge.pcm";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkPermission();
        } else {
            todo();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void todo() {
        Utils.copyFileToSdcard(this, mediaName1,
                StorageUtil.getExternalFilesDir(this, null) + File.separator + mediaName1);
        Utils.copyFileToSdcard(this, mediaName2,
                StorageUtil.getExternalFilesDir(this, null) + File.separator + mediaName2);

        karaokeManager = new KaraokeManager(
                StorageUtil.getExternalFilesDir(this, null) + File.separator + mediaName1,
                StorageUtil.getExternalFilesDir(this, null) + File.separator + mediaName2);

//        karaokeManager.setPCMPath("","");
        karaokeManager.setOnPrepareListener(new KaraokeManager.OnPrepareListener() {
            @Override
            public void onPrepared() {
                karaokeManager.start();
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void checkPermission() {
        List<String> permissions = new ArrayList<>();
        // 检查是否获取了权限
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        if (permissions.size() == 0) {
            // 已经有了权限
            todo();
        } else {
            // 请求所缺少的权限，在onRequestPermissionsResult中再看是否获得权限，如果获得权限就可以调用logAction，否则申请到权限之后再调用。
            String[] requestPermissions = new String[permissions.size()];
            permissions.toArray(requestPermissions);
            requestPermissions(requestPermissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case PERMISSION_REQUEST_CODE:
                if (grantResults.length > 0) {
                    boolean hasPermission = true;
                    for (int grantResult : grantResults) {
                        if (grantResult != PackageManager.PERMISSION_GRANTED) {
                            hasPermission = false;
                            break;
                        }
                    }
                    if (hasPermission) {
                        // 已经有了权限
                        todo();
                    } else {
                        // 如果用户没有授权，那么应该说明意图，引导用户去设置里面授权。
                        Toast.makeText(this, "应用缺少必要的权限！请点击\"权限\"，打开所需要的权限。", Toast.LENGTH_LONG).show();
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                        finish();
                    }
                }
                break;
            default:
        }
    }

    public void prepare(View view) {
        karaokeManager.prepare();
    }

    public void merge(View view) {
        byte[] buffer1 = new byte[2048];
        byte[] buffer2 = new byte[2048];
        byte[] buffer3 = new byte[2048];
        short temp1, temp2; // 从文件还原的声音点
        int temp;   // 两个声音点相加后的值，有可能超过short（一个声音点的值），所以用int接收

        FileInputStream fis1 = null;
        FileInputStream fis2 = null;
        FileOutputStream mergeos = null;
        try {
            fis1 = new FileInputStream(StorageUtil.getExternalFilesDir(this, null) + File.separator + decodPcmName1);
            fis2 = new FileInputStream(StorageUtil.getExternalFilesDir(this, null) + File.separator + decodPcmName2);
            mergeos = new FileOutputStream(StorageUtil.getExternalFilesDir(this, null) + File.separator + mergePcm);

            boolean end1 = false, end2 = false;

            while (!end1 || !end2) {
                if (!end1) {
                    end1 = (fis1.read(buffer1) == -1);
                    System.arraycopy(buffer1, 0, buffer3, 0, buffer1.length);
                }

                if (!end2) {
                    end2 = (fis2.read(buffer2) == -1);

                    /**
                     * 16 bit 采样深度，一个声音采样点是两个字节，也就是低八位在前面，高八位在后面
                     * 如果是双声道，则左声道的低八位高八位，右声道的低八位高八位。。。依次存储在文件上
                     * 所以这里 i += 2 是循环依次取两个字节也就是一个声音点，进行低高八位操作
                     */
                    for (int i = 0; i < buffer2.length; i += 2) {
                        temp1 = (short) ((buffer1[i] & 0xff) | (buffer1[i + 1] & 0xff) << 8);
                        temp2 = (short) ((buffer2[i] & 0xff) | (buffer2[i + 1] & 0xff) << 8);
                        temp = temp1 + temp2;
                        // 相加后的声音点不能超过声音波形范围
                        if (temp > 32767) { // 波形最高点
                            temp = 32767;
                        } else if (temp < -32768) { // 波形最低点
                            temp = -32768;
                        }

                        buffer3[i] = (byte) (temp & 0xff);
                        buffer3[i + 1] = (byte) ((temp >>> 8) & 0xff);
                    }
                }
                mergeos.write(buffer3);
            }
            Log.d(TAG, "合成完成！");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (fis1 != null) {
                try {
                    fis1.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (fis2 != null) {
                try {
                    fis2.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (mergeos != null) {
                try {
                    mergeos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
