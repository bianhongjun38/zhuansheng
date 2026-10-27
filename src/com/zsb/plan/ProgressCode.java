package com.zsb.plan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * 进度码 —— 把全部进度压成一个可以直接粘贴的短字符串。
 *
 * 为什么需要它：
 *  · 「每个用户进度不一」如果靠云服务器，就得有账号、有网、有维护成本
 *  · 实际使用中一个人就一两台设备，真正要解决的是「换手机不丢进度」
 *  · 所以做成离线码：压 + Base32 + 校验和，复制粘贴即可搬移
 *
 * 格式：ZSB1 + Base32( deflate( json ) ) + 4 位校验
 * 前缀和校验和用来在粘贴错时立刻报错，而不是悄悄导入半份数据。
 */
public class ProgressCode {

    private static final String MAGIC = "ZSB1";
    // Crockford Base32：去掉 I L O U，避免和 1 0 混淆
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** 把 exportJson() 的结果编码成进度码。 */
    public static String encode(String json) {
        try {
            byte[] raw = json.getBytes("UTF-8");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DeflaterOutputStream d = new DeflaterOutputStream(bo, new Deflater(9), 8192);
            d.write(raw);
            d.finish();
            d.close();
            String b32 = base32(bo.toByteArray());
            return MAGIC + "-" + group(b32) + "-" + checksum(b32);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解出 JSON。失败返回 null，不抛异常。 */
    public static String decode(String code) {
        try {
            if (code == null) return null;
            // 容忍用户粘贴时带了空格、换行、大小写混用
            String s = code.trim().toUpperCase().replaceAll("[\\s\\-]", "");
            if (!s.startsWith(MAGIC)) return null;
            s = s.substring(MAGIC.length());
            if (s.length() < 6) return null;

            String sum = s.substring(s.length() - 4);
            String body = s.substring(0, s.length() - 4);
            if (!checksum(body).equals(sum)) return null;   // 抄错了

            byte[] packed = unBase32(body);
            if (packed == null) return null;

            InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(packed));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    // ── 编码细节 ──────────────────────────────────────────
    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte b : data) {
            buf = (buf << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buf << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    private static byte[] unBase32(String s) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = ALPHABET.indexOf(s.charAt(i));
            if (v < 0) return null;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bo.write((buf >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return bo.toByteArray();
    }

    /** 4 位校验：对字符做加权和，抄错一位基本必错。 */
    private static String checksum(String s) {
        long h = 5381;
        for (int i = 0; i < s.length(); i++) {
            h = ((h * 33) + s.charAt(i)) & 0xFFFFFFFFL;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt((int) ((h >> (i * 5)) & 31)));
        }
        return sb.toString();
    }

    /** 每 5 个字符加一个横杠，方便肉眼核对和分行粘贴。 */
    private static String group(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 5 == 0) sb.append('-');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}
