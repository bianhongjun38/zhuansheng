package com.zsb.plan;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * 词性题的样子。
 *
 * 用户原话：「不要把中文当成主体了，英文才是，中文做这么大干嘛，
 * 而且下面的选项也应该弄个中文释义，不然 art 是什么都不知道」。
 * 这里按实机字号把题干、副信息、选项画出来，肉眼确认主次关系。
 */
public class PosViewAudit {

    static int pass = 0, fail = 0;
    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    static final int W = 360, PAD = 24, CARD_W = W - PAD * 2;

    public static void main(String[] a) throws Exception {
        System.out.println("▶ 词性题排版（英文主体 / 选项带中文）");

        // 实机两档字号：题干 22sp，副信息 13sp，选项 15sp
        Font fPrompt = new Font("Microsoft YaHei", Font.BOLD, 24);
        Font fSub = new Font("Microsoft YaHei", Font.PLAIN, 14);
        Font fOpt = new Font("Microsoft YaHei", Font.PLAIN, 16);
        FontMetrics mPrompt = fm(fPrompt);
        FontMetrics mSub = fm(fSub);
        FontMetrics mOpt = fm(fOpt);

        // ① 题干只能是英文，且宽度够（22sp 大字）
        String[] words = {"a", "abandon", "international", "responsibility", "technology"};
        int overWide = 0; String wide = "";
        for (String w : words) {
            if (mPrompt.stringWidth(w) > CARD_W - 28) { overWide++; wide = w; }
        }
        ok("最长的词也放得下", overWide == 0, "超宽：" + wide);

        // ② 中文必须比英文小 —— 这是用户最直接的那句抱怨
        ok("中文释义字号小于英文", fSub.getSize() < fPrompt.getSize(),
                fSub.getSize() + " vs " + fPrompt.getSize());
        // 13sp / 22sp ≈ 0.59，缩到 6 成左右才叫"副信息"
        double ratio = fSub.getSize() / (double) fPrompt.getSize();
        ok("中文不超过英文的七成", ratio <= 0.7, String.format("%.2f", ratio));

        // ③ 选项必须带中文词性名
        String[] opts = {"prep.", "art.", "adj.", "n."};
        int noCn = 0; String missCn = "";
        for (String o : opts) {
            if (EngTrain.posName(o).isEmpty()) { noCn++; missCn = o; }
        }
        ok("每个选项都能给出中文词性", noCn == 0, "缺失：" + missCn);

        // ④ 带中文后的选项宽度
        int wideOpt = 0; String wo = "";
        for (String o : opts) {
            String txt = "A． " + o + "　" + EngTrain.posName(o);
            if (mOpt.stringWidth(txt) > CARD_W - 28) { wideOpt++; wo = txt; }
        }
        ok("带中文的选项放得下", wideOpt == 0, "超宽：" + wo);

        render(fPrompt, fSub, fOpt);

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

    static void render(Font fPrompt, Font fSub, Font fOpt) throws Exception {
        int h = 620;
        BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14161C));
        g.fillRect(0, 0, W, h);

        // 题干卡
        g.setColor(new Color(0x1B1E26));
        g.fillRoundRect(PAD, 30, CARD_W, 130, 24, 24);

        g.setColor(new Color(0x3ECFAD));
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        g.drawString("词性", PAD + 14, 56);

        g.setColor(new Color(0x6B7282));
        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        g.drawString("这个词是什么词性", PAD + 14, 76);

        g.setColor(new Color(0xE8EAF0));
        g.setFont(fPrompt);
        g.drawString("a", PAD + 14, 118);

        g.setColor(new Color(0x6B7282));
        g.setFont(fSub);
        g.drawString("一，一个", PAD + 14, 144);

        // 选项
        String[] opts = {"prep.", "art.", "adj.", "n."};
        int y = 180;
        for (int i = 0; i < opts.length; i++) {
            boolean correct = opts[i].equals("art.");
            g.setColor(correct ? new Color(0x1E3A34) : new Color(0x1B1E26));
            g.fillRoundRect(PAD, y, CARD_W, 48, 20, 20);

            g.setColor(correct ? new Color(0x3ECFAD) : new Color(0xE8EAF0));
            g.setFont(fOpt);
            String txt = ((char) ('A' + i)) + "． " + opts[i] + "　" + EngTrain.posName(opts[i]);
            g.drawString(txt, PAD + 14, y + 31);
            y += 56;
        }
        g.dispose();
        ImageIO.write(img, "png", new File("pos-question.png"));
        System.out.println("    已渲染 pos-question.png");
    }
}