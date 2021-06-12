package com.husky.mp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.husky.mp.karaoke.KaraokeManager;
import com.husky.mp.util.StorageUtil;
import com.husky.mp.util.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class KaraokeActivity extends AppCompatActivity {

    private static final String TAG = "KaraokeActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private Switch switchButton;

    private String original = "狂浪-原唱.mp3";   // 原唱
    private String music = "狂浪-伴唱.mp3";      // 伴唱
    private String zrce = "狂浪.zrce";           // 歌词
    //    private String original = "5d4e894aa559b3272a8707a27b3b2323.mp3";   // 原唱
//    private String music = "fd0e957aa8025da1755c7ffe657ae752.mp3";      // 伴唱
//    private String originalPcm = "originalPcm.pcm";      // 伴唱
//    private String musicPcm = "musicPcm.pcm";      // 伴唱

    private KaraokeManager karaokeManager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_karaoke);
        switchButton = findViewById(R.id.switchButton);
        switchButton.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                karaokeManager.setOriginal(isChecked);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkPermission();
        } else {
            // 无需动态获取权限
            copyMedia();
        }
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
            copyMedia();
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
                        copyMedia();
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

    /**
     * 拷贝媒体资源
     */
    private void copyMedia() {
        Utils.copyFileToSdcard(this, original,
                StorageUtil.getExternalFilesDir(this, null) + File.separator + original);
        Utils.copyFileToSdcard(this, music,
                StorageUtil.getExternalFilesDir(this, null) + File.separator + music);
        Utils.copyFileToSdcard(this, zrce,
                StorageUtil.getExternalFilesDir(this, null) + File.separator + zrce);
    }

    public void start(View view) {
        // 可以写在前面其他初始化的时候
        if (karaokeManager == null) {
            karaokeManager = new KaraokeManager(
                    StorageUtil.getExternalFilesDir(this, null) + File.separator + original,
                    StorageUtil.getExternalFilesDir(this, null) + File.separator + music);

            karaokeManager.setOnPrepareListener(new KaraokeManager.OnPrepareListener() {
                @Override
                public void onPrepared() {
//                    karaokeManager.setPCMPath(StorageUtil.getExternalFilesDir(KaraokeActivity.this, null) + File.separator + originalPcm,
//                            StorageUtil.getExternalFilesDir(KaraokeActivity.this, null) + File.separator + musicPcm);
                    karaokeManager.start();
                }
            });
        }
        // 调用该api才是真正准备启动编码器等操作
        karaokeManager.prepare();
    }

    public void pause(View view) {
        karaokeManager.pause();
    }

    public void resume(View view) {
        karaokeManager.resume();
    }

    public void stop(View view) {
        karaokeManager.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        karaokeManager.release();
    }
}
