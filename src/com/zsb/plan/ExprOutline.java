package com.zsb.plan;

/**
 * 「怎么写 → 画出来长什么样」对照表的数据。
 *
 * 用户原话："函数图像那个你得写 sqrt 和 abs 和 exp 对照什么，不然谁知道"。
 * 光有 16 个模板格子和一句"sin cos tan ln lg sqrt abs exp 都认"，
 * 只是把函数名堆给用户 —— 不知道 sqrt 是什么形状、abs 为什么是 V 形、
 * exp 和 x^2 有什么区别，模板再多也用不上。
 *
 * 每一条给三样东西：
 *   write  在输入框里怎么写（可以直接点，点了就填进输入框）
 *   name   中文叫什么（"开平方"，不是"平方根函数"这种半翻译）
 *   shape  画出来是什么形状（"只画 x≥0 那半边，从原点往上走，越来越慢"）
 *   ask    一个能自己验证的问题（"x=9 时是 3，不是 81"）—— 让形状可以被检验
 *
 * sample 是给缩略图采样用的表达式，必须与 write 同源，
 * 由 ExprOutlineAudit 断言：sample 解析得了、定义域与 shape 描述一致。
 */
public class ExprOutline {

    public static class Item {
        public final String write;   // 输入框里怎么写
        public final String name;    // 中文名
        public final String shape;   // 形状人话
        public final String ask;     // 自己验一下
        public final String sample;  // 缩略图采样用（与 write 同源）
        public final int color;      // 曲线颜色

        public Item(String write, String name, String shape, String ask,
                    String sample, int color) {
            this.write = write;
            this.name = name;
            this.shape = shape;
            this.ask = ask;
            this.sample = sample;
            this.color = color;
        }
    }

    /** 每组一个标题，按"最常用 → 越来越少用"排。 */
    public static final String[] GROUPS = {
            "平方 / 开方 / 绝对值 —— 最容易被搞混的三个",
            "指数与对数 —— 长得像但方向相反",
            "三角函数 —— 会来回摆的",
            "其它常用"
    };

    public static Item[] all() {
        return new Item[]{
            // ── 组一：幂、根、绝对值 ──
            new Item("x^2", "平方", "抛物线，左右对称，最低点在原点",
                    "x 取 3 和 −3，结果都是 9 —— 所以左右一样高",
                    "x^2", 0xFF3ECFAD),
            new Item("sqrt(x)", "开平方（正的）", "只画 x ≥ 0 那半边；从原点往上，越走越慢，像躺下的抛物线",
                    "x=9 时是 3，不是 81；x 填负数画不出来",
                    "sqrt(x)", 0xFF4EA8FF),
            new Item("abs(x)", "绝对值", "V 形，尖角在原点；左边那半是 x^2 翻转上来的，但是直的",
                    "x 取 −5 和 5 都是 5 —— 负号被吃掉了",
                    "abs(x)", 0xFFE0A94A),
            new Item("x", "一次函数", "一条斜直线，过原点，斜率 1",
                    "x 每加 1，y 也加 1",
                    "x", 0xFF9B8CFF),
            new Item("1/x", "倒数", "两支曲线，永远不碰 x 轴和 y 轴；x 越接近 0 越往上冲",
                    "x=1000 时几乎贴住 x 轴；x=0 画不出来",
                    "1/x", 0xFFE05A54),

            // ── 组二：指数与对数 ──
            new Item("exp(x)", "自然指数", "从左边几乎贴着 x 轴，过了原点猛往上冲，比 x^2 快得多",
                    "x=0 时 y=1（不是 0）；x 每加 1，y 乘 2.718",
                    "exp(x)", 0xFF3ECFAD),
            new Item("2^x", "二的 x 次方", "形状和 e^x 一样，但涨得慢一点",
                    "x=3 时是 8；x=−1 时是 0.5（不是负数）",
                    "2^x", 0xFF4EA8FF),
            new Item("ln(x)", "自然对数", "e^x 的镜像；只画 x > 0，x 很小时往下掉得很凶，之后平缓",
                    "x=1 时 y=0；x=e≈2.718 时 y=1",
                    "ln(x)", 0xFFE0A94A),
            new Item("lg(x)", "常用对数（以 10 为底）", "形状同 ln，但更平；x=10 才到 1",
                    "x=100 时 y=2；x=0 画不出来",
                    "lg(x)", 0xFF9B8CFF),

            // ── 组三：三角函数 ──
            new Item("sin(x)", "正弦", "在 −1 到 1 之间来回摆，从原点出发先往上",
                    "x=0 时 y=0；最高只到 1，永远超不过",
                    "sin(x)", 0xFF3ECFAD),
            new Item("cos(x)", "余弦", "和 sin 一样的波浪，但起点在最高的 1",
                    "x=0 时 y=1（sin 是 0）—— 这是两者唯一好记的区别",
                    "cos(x)", 0xFF4EA8FF),
            new Item("tan(x)", "正切", "一段段往上冲的竖条，每段之间断开（那些位置画不出来）",
                    "x 接近 90° 时冲向无穷；它不连续",
                    "tan(x)", 0xFFE05A54),
            new Item("sin(x)/x", "sinc（抽样函数）", "中间鼓起一个包，两边越来越小地抖动，x=0 处取极限 1",
                    "x 越大越贴住 x 轴；x=0 那点虽然写成分式但值是 1",
                    "sin(x)/x", 0xFFE0A94A),

            // ── 组四：其它常用 ──
            new Item("x^3", "立方", "从下往上穿原点，左边在下面、右边在上面（不是对称）",
                    "x=−2 时是 −8 —— 负号保留，和 x^2 不一样",
                    "x^3", 0xFF3ECFAD),
            new Item("1/(1+x^2)", "钟形（洛伦兹）", "中间最高 1，两边对称地滑向 0，像一口钟",
                    "x=0 时 y=1；x=±1 时降到 0.5",
                    "1/(1+x^2)", 0xFF4EA8FF),
            new Item("x^2*sin(1/x)", "抖动（无限振荡）", "越靠近原点抖得越快，快到画不出来",
                    "x 越小摆动越密；原点附近采样会失真",
                    "x^2*sin(1/x)", 0xFFE05A54),
        };
    }

    /** 每条属于哪一组（与 GROUPS 对应），用于分节显示。 */
    public static int groupOf(int i) {
        if (i < 5) return 0;
        if (i < 9) return 1;
        if (i < 13) return 2;
        return 3;
    }
}
