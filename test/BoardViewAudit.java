package com.zsb.plan;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 登录页 + 排行榜的可视审查。
 *
 * 为什么这两个界面必须画出来看：
 *   它们是新加的，而「用户名 + 密码」和「排行榜」这两类界面
 *   最容易出的问题不是逻辑错，是排版认不出来 ——
 *   名字太长挤掉分数、正确率和轮数糊成一片、名次颜色和背景糊在一起。
 *   这些问题跑断言查不出来，只有把像素画出来才看得见。
 */
public class BoardViewAudit {

    static int pass = 0, fail = 0;
    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    static final int W = 360, PAD = 24, CARD_W = W - PAD * 2;

    public static void main(String[] a) throws Exception {
        System.out.println("▶ 登录页 / 排行榜排版");

        Font fName = new Font("Microsoft YaHei", Font.BOLD, 18);
        Font fSub = new Font("Microsoft YaHei", Font.PLAIN, 15);
        Font fScore = new Font("Microsoft YaHei", Font.BOLD, 22);

        // ① 分数列必须给最坏情况留够位置。
        //    第一版这里断言的是「20 字用户名放得下」——
        //    那条永远会红，而且方向错了：长名字本来就该被截断，
        //    真正不能妥协的是「分数不能被挤掉」。改成验分数列。
        int scoreW = fm(fScore).stringWidth("9999");
        int rankW = 26;   // 实机名次列宽 dp
        int spare = CARD_W - rankW - scoreW - 24;   // 24 = 左右 padding
        ok("4 位数分数列放得下", scoreW <= CARD_W - rankW,
                scoreW + "px 超过 " + (CARD_W - rankW) + "px");
        ok("名字列还有余量给名字", spare > 60, "只剩 " + spare + "px");

        // ② 明细行格式必须和 BoardView 里真实拼的一致。
        //    第一版这里自己写了「N 轮 · 共 M 题 · 正确率 X%」——
        //    实机 BoardView 当时也是这么写的，但审计没有真的去读源码，
        //    等于测了份副本，改哪边都不会红。改成读源码断言。
        String src = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/com/zsb/plan/BoardView.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        // 明细行后来又收窄过一次：场次/题数跟节点数量级接近、信息重复，
        // 换成「层 · 金币 · 正确率」—— 层=爬到哪，金币=攒了多少，正确率=打得准不准。
        // 断言要钉在**当前**这版真实拼接上，否则源码改了这里还绿，就等于没测。
        // 明细行现在还带上了「第几张地图」—— 层数口径统一后，
        // 光看「第 N 层」不知道在第几张图上。断言同步钉到新格式。
        boolean hasShortDetail = src.contains("\"第\" + (floor + 1) + \"层 · 图\" + mapNo");
        ok("明细行显示层/图/金/正确率四段", hasShortDetail,
                "BoardView 里的明细行格式变了，请同步这条断言");

        // 用户要求不显示等级：等级等于层数，并排写是重复信息。
        // 这条防止以后又把 Lv. 加回来。
        ok("排行榜不显示 Lv.", !src.contains("\"Lv.\""),
                "BoardView 又把 Lv. 加回明细行了");

        // 明细行必须用**它自己的字号**来量。
        // BoardView 里 sub.setTextSize(11)，这里原来误用了 fScore(22pt) ——
        // 尺寸翻倍，于是不管怎么写都"放不下"，是审计自己造成的虚警。
        // 标题字号是 11sp，Android sp≈px（160dpi 基准），直接用 11pt 对应。
        Font fDetail = new Font("Microsoft YaHei", Font.PLAIN, 11);
        java.awt.FontMetrics fm2 = new java.awt.Canvas().getFontMetrics(fDetail);
        int w = fm2.stringWidth("第99层 · 图99 · ✦9999 · 100%");
        ok("四段明细在最坏情况下放得下", w <= spare,
                w + "px > 余量 " + spare + "px（names 会被挤掉）");

        // ③ 排行榜必须是「玩家主动上传」，不能退回自动上报。
        //    这条不是格式检查，是行为回归：加回自动上报会让榜上的
        //    金币变成一堆战报的累加，和玩家界面上的数字对不上。
        ok("排行榜有主动上传按钮", src.contains("上传我的进度"),
                "BoardView 里找不到上传按钮文案");
        ok("上传走快照接口而非战报接口", src.contains("Api.postSnapshot"),
                "上传又改回 postBattle 了 —— 那个接口会累加");
        ok("空榜文案不再说自动记录", !src.contains("打赢一场战斗就会出现在这里"),
                "空榜还在承诺自动上榜，和实际行为不符");

        String bsrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/com/zsb/plan/GBattleView.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        ok("战斗结束不再自动上报", !bsrc.contains("uploadPendingBattles()"),
                "GBattleView 又把自动上报加回来了");
        ok("榜上提示我上次上传时间", src.contains("我上次上传"),
                "缺少「我上次上传」提示，玩家会以为自动同步");
        // 401 必须自动拉起登录并接着传，不能只弹一句「登录过期」。
        // 这是真实用户踩到的坑：token 因别处登录而作废，他只看到一句提示，
        // 不知道要去哪儿、也不知道点完还得再点一次上传。
        ok("401 自动拉起重新登录", src.contains("askRelogin"),
                "401 只弹提示，不会自动重新登录");
        ok("重登后自动续传", src.contains("doUpload();"),
                "重新登录后没有接着上传；玩家要自己再点一次");

        // (5) 精英必须掉特殊词/道具。原来精英只多给 50 金币，特殊词唯一
        //     来源是商店一次挂一个 —— 整局下来手里常年 1~2 个，词见不全。
        ok("精英结算会掉东西", bsrc.contains("addEliteDrop()"),
                "GBattleView 不再给精英掉落，精英又只剩金币");
        ok("只有精英档才掉", bsrc.contains("elite"),
                "掉落门槛改了，普通怪也会掉，精英就没有区别了");
        // ④ 词汇塔顶栏必须一行放得下。
        //    原来顶栏和标题同一行、weight=1 抢剩余宽度，360dp 上只能写到
        //    「…特殊词 0 道」，后面被折到第二行且跟着 right 对齐，看着像乱码。
        //    现在给它独立一行 + 压短标签，这条断言锁住「最坏情况仍放得下」。
        String msrc = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/com/zsb/plan/GModeView.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        ok("顶栏不再用 \\n 手工折行", !msrc.contains("\"\\n第 \" + store.floorNo()"),
                "顶栏又拼回带换行的长字符串了");
        // 只检查顶栏那一段，不能整份源码找 —— 商店里「特殊词 · xxx」是正常文案，
        // 第一版写成全文件查找，直接把商店那行判成违规，是断言自己写宽了。
        int headAt = msrc.indexOf("private void updateHead()");
        String headSrc = headAt > 0
                ? msrc.substring(headAt, Math.min(msrc.length(), headAt + 3000)) : "";
        ok("顶栏没有塞不下的长标签",
                !headSrc.contains("特殊词 \" + store.specialCount()"),
                "顶栏又用回「特殊词 N」这种长标签，360dp 屏会折行");
        // 用真实 12sp 度量最坏情况
        Font fHead = new Font("Microsoft YaHei", Font.PLAIN, 12);
        java.awt.FontMetrics fmh = new java.awt.Canvas().getFontMetrics(fHead);
        String worstHead = "\u276499/99\u3000\u27269999\u3000\u7b2c99\u5c42"
                + "\u3000\u7b2c9\u56fe\u3000\u7279\u6b8a99\u3000\u9053\u517799";
        int hw = fmh.stringWidth(worstHead);
        ok("顶栏最坏情况放得下", hw <= 332, hw + "px > 332dp");
        // 同一行里不能同时有两个 weight=1 抢宽度的兄弟（标题已经占了）
        ok("顶栏信息独立成行", msrc.contains("infoWrap"),
                "顶栏信息没有独立容器，仍和标题抢宽度");

        // 名字必须靠原生截断，不能靠手算宽度。
        // 这里必须钉死到 nm 这个 View 上 —— 第一版写的是
        // src.contains("setEllipsize")，把 nm 那行删掉之后
        // 明细行 sub.setEllipsize 还在，断言照样绿。
        // 审计自身的漏洞，比它要防的 bug 更危险。
        ok("用户名用了原生省略号", src.contains("nm.setEllipsize"),
                "BoardView 的 nm 没有 setEllipsize，长名字会挤掉分数");

        // 用收窄后的真实格式算宽度
        String detail = "第30层 · 9999金 · 100%";
        int detW = fm(fSub).stringWidth(detail);
        ok("收窄后的明细放得下", detW <= spare, detW + "px > " + spare + "px");

        // ③ 名次 1-3 的颜色必须和背景有对比
        int[] rankColors = {0xE0A94A, 0xC3C9D6, 0xB08968};
        int bg = 0x1C1F27;
        int lowContrast = 0; String worst = "";
        for (int c : rankColors) {
            double cr = contrast(c, bg);
            if (cr < 3.0) { lowContrast++; worst = String.format("#%06X 对比 %.1f", c, cr); }
        }
        ok("前三名颜色看得清", lowContrast == 0, worst);

        // ④ 「我」那一行的高亮要和普通行区分得开
        double crMe = contrast(0x3ECFAD, 0x20323A);
        ok("自己那行的高亮够明显", crMe >= 3.0, String.format("对比 %.1f", crMe));

        renderLogin();
        renderBoard(fName, fSub, fScore);

        System.out.println();
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    static FontMetrics fm(Font f) {
        BufferedImage i = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = i.createGraphics();
        g.setFont(f);
        FontMetrics m = g.getFontMetrics();
        g.dispose();
        return m;
    }

    /** WCAG 相对亮度对比度。用来验文字在背景上到底看不看得见。 */
    static double contrast(int fg, int bgc) {
        double a = lum(fg), b = lum(bgc);
        double hi = Math.max(a, b), lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    static double lum(int c) {
        double[] ch = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = ((c >> (16 - i * 8)) & 0xFF) / 255.0;
            ch[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
    }

    static void renderLogin() throws Exception {
        int h = 560;
        BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14161C));
        g.fillRect(0, 0, W, h);

        g.setColor(new Color(0xC3C9D6));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 22));
        g.drawString("✕", PAD, 40);
        g.setColor(new Color(0xE8EAF0));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 17));
        g.drawString("账号", PAD + 44, 42);

        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        g.drawString("起个用户名，设个密码。", PAD, 84);
        g.drawString("进度会存到云端，换手机也不会丢。", PAD, 104);

        // 用户名
        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.drawString("用户名", PAD, 150);
        g.setColor(new Color(0x1C1F27));
        g.fillRoundRect(PAD, 160, CARD_W, 46, 24, 24);
        g.setColor(new Color(0xE8EAF0));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        g.drawString("小明", PAD + 14, 189);

        // 密码
        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.drawString("密码（至少 4 位）", PAD, 236);
        g.setColor(new Color(0x1C1F27));
        g.fillRoundRect(PAD, 246, CARD_W, 46, 24, 24);
        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 15));
        g.drawString("密码", PAD + 14, 275);

        // 状态行
        g.setColor(new Color(0xE05A54));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        g.drawString("这个名字已经有人用了，换一个", PAD, 322);

        // 主按钮
        g.setColor(new Color(0x3ECFAD));
        g.fillRoundRect(PAD, 336, CARD_W, 48, 24, 24);
        g.setColor(new Color(0x10131A));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        String btn = "注册并登录";
        int bw = g.getFontMetrics().stringWidth(btn);
        g.drawString(btn, PAD + (CARD_W - bw) / 2, 366);

        g.setColor(new Color(0x3ECFAD));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        String sw = "已有账号？点这里登录";
        int swW = g.getFontMetrics().stringWidth(sw);
        g.drawString(sw, PAD + (CARD_W - swW) / 2, 414);

        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.drawString("忘记密码：找管理员重置。", PAD, 462);
        g.drawString("数据只在你和设备之间传输，不上报任何第三方。", PAD, 482);

        g.dispose();
        ImageIO.write(img, "png", new File("login-page.png"));
        System.out.println("    已渲染 login-page.png");
    }

    static void renderBoard(Font fName, Font fSub, Font fScore) throws Exception {
        String[] names = {"小明", "一二三四五六七八九十一二三四五六七八九十",
                "张三", "李四同学", "王五"};
        int[] scores = {186, 152, 152, 97, 61};
        int[] totals = {200, 170, 180, 140, 80};
        int[] rounds = {4, 3, 3, 2, 1};

        int h = 520;
        BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14161C));
        g.fillRect(0, 0, W, h);

        g.setColor(new Color(0xC3C9D6));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 22));
        g.drawString("✕", PAD, 40);
        g.setColor(new Color(0xE8EAF0));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 17));
        g.drawString("英语闯关榜", PAD + 44, 42);

        // tabs
        g.setColor(new Color(0x3ECFAD));
        g.fillRoundRect(PAD, 56, (CARD_W - 8) / 2, 40, 20, 20);
        g.setColor(new Color(0x10131A));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        String t1 = "今日";
        g.drawString(t1, PAD + ((CARD_W - 8) / 2 - g.getFontMetrics().stringWidth(t1)) / 2, 81);

        g.setColor(new Color(0x1C1F27));
        g.fillRoundRect(PAD + (CARD_W - 8) / 2 + 8, 56, (CARD_W - 8) / 2, 40, 20, 20);
        g.setColor(new Color(0xC3C9D6));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        String t2 = "近 7 天";
        g.drawString(t2, PAD + (CARD_W - 8) / 2 + 8
                + ((CARD_W - 8) / 2 - g.getFontMetrics().stringWidth(t2)) / 2, 81);

        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        g.drawString("2026-10-27　按答对题数排", PAD, 118);

        int y = 132;
        for (int i = 0; i < names.length; i++) {
            boolean me = i == 0;
            g.setColor(me ? new Color(0x20323A) : new Color(0x1C1F27));
            g.fillRoundRect(PAD, y, CARD_W, 62, 24, 24);

            // 名次
            int rc = i == 0 ? 0xE0A94A : i == 1 ? 0xC3C9D6 : i == 2 ? 0xB08968 : 0x6B7282;
            g.setColor(new Color(rc));
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
            g.drawString(String.valueOf(i + 1), PAD + 10, y + 36);

            // 名字（超长要截断）
            g.setColor(new Color(me ? 0x3ECFAD : 0xE8EAF0));
            g.setFont(me ? fName.deriveFont(Font.BOLD) : fName);
            FontMetrics mn = g.getFontMetrics();
            int avail = CARD_W - 26 - fm(fScore).stringWidth("9999") - 24;
            // 「（我）」标记必须和实机一致：BoardView 里是 name + "（我）"，
            // 渲染图少了这个后缀就比实机短，截断位置也就对不上
            String nm = me ? (names[i] + "（我）") : names[i];
            if (mn.stringWidth(nm) > avail) {
                while (nm.length() > 1 && mn.stringWidth(nm + "…") > avail) {
                    nm = nm.substring(0, nm.length() - 1);
                }
                nm = nm + "…";
            }
            g.drawString(nm, PAD + 40, y + 26);

            // 明细
            g.setColor(new Color(0x6B7282));
            g.setFont(fSub.deriveFont(11f));
            int rate = totals[i] > 0 ? scores[i] * 100 / totals[i] : 0;
            // 必须和 BoardView.row() 里的真实格式一字不差，
            // 否则画出来的图和实机不同，审查就白做了
            g.drawString(rounds[i] + " 轮 · " + totals[i] + " 题 · " + rate + "%",
                    PAD + 40, y + 46);

            // 分数
            g.setColor(new Color(me ? 0x3ECFAD : 0xE8EAF0));
            g.setFont(fScore);
            int sw = g.getFontMetrics().stringWidth(String.valueOf(scores[i]));
            g.drawString(String.valueOf(scores[i]), PAD + CARD_W - 12 - sw, y + 38);

            y += 70;
        }

        g.dispose();
        ImageIO.write(img, "png", new File("eng-board.png"));
        System.out.println("    已渲染 eng-board.png");
    }
}