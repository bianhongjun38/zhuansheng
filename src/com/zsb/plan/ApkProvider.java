package com.zsb.plan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件提供者 —— 只为把下载好的 APK 交给系统安装器。
 *
 * 为什么不用 androidx 的 FileProvider：这个 App 是手工 aapt2/javac/d8 打的包，
 * 不带任何 androidx 依赖。而 Android 7.0+ 禁止直接传 file:// 给别的应用
 * （FileUriExposedException），所以必须自己实现一个 content:// 提供者。
 *
 * 只暴露 cache 目录下的单个文件，路径写死，不接受任意路径。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.zsb.plan.updates";
    public static final String NAME = "update.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File apk() {
        return new File(getContext().getCacheDir(), NAME);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = apk();
        if (!f.exists()) throw new FileNotFoundException("update.apk not found");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] selArgs, String sort) {
        File f = apk();
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = NAME;
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
