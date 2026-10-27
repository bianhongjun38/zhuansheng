package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 登录 / 注册。一个全屏 Dialog：用户名 + 密码 + 一个按钮。
 *
 * 为什么是「用户名 + 密码」而不是邮箱/手机号：
 *   20 个同学，都是一个班的。要邮箱就得收验证码，要手机号就得实名，
 *   这两样都是为了防陌生人的，这里没有陌生人。
 *   忘记密码就找管理员重置，最省事。
 *
 * 密码不落盘：登录成功只把 token 存进 SharedPreferences，
 * 之后所有请求带 token。token 是长期凭证，再存一份密码只是多一个泄漏面。
 */
public class LoginView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAD;
    private static final int C_RED = 0xFFE05A54;

    private final Activity act;
    private Dialog dlg;
    private EditText etName, etPwd;
    private TextView tvStatus, btnMain, btnSwitch;
    private boolean registering;
    private boolean busy;

    /** 登录/注册成功回调，主界面接这个去刷标题和触发首次同步。 */
    public interface OnLoggedIn {
        void onLoggedIn(Account acc);
    }

    private OnLoggedIn onLoggedIn;

    public LoginView(Activity a) {
        this.act = a;
    }

    public void setOnLoggedIn(OnLoggedIn cb) {
        this.onLoggedIn = cb;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        return g;
    }

    public void show() {
        if (dlg != null && dlg.isShowing()) { dlg.show(); return; }
        build();
        dlg.show();
    }

    public boolean isShowing() {
        return dlg != null && dlg.isShowing();
    }

    // ── 界面 ──────────────────────────────────────────────

    private void build() {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(14), dp(8));

        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(22);
        close.setTextColor(C_FG2);
        close.setPadding(dp(6), dp(4), dp(14), dp(4));
        close.setOnClickListener(v -> dlg.dismiss());
        top.addView(close);

        TextView title = new TextView(act);
        title.setText("账号");
        title.setTextSize(17);
        title.setTextColor(C_FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        ScrollView sc = new ScrollView(act);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(6), dp(18), dp(24));
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 说明
        TextView hint = new TextView(act);
        hint.setText("起个用户名，设个密码。"
                + "\n进度会存到云端，换手机也不会丢。");
        hint.setTextSize(13);
        hint.setTextColor(C_FG3);
        hint.setLineSpacing(dp(3), 1f);
        body.addView(hint, lp(0));

        // 用户名
        body.addView(label("用户名"), lp(18));
        etName = field("例如：小明", false);
        etName.setSingleLine(true);
        etName.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.addView(etName, lp(8));

        // 密码
        body.addView(label("密码（至少 4 位）"), lp(14));
        etPwd = field("密码", true);
        etPwd.setSingleLine(true);
        etPwd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPwd.setImeOptions(EditorInfo.IME_ACTION_DONE);
        etPwd.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); return true; }
            return false;
        });
        body.addView(etPwd, lp(8));

        // 状态行
        tvStatus = new TextView(act);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(C_RED);
        tvStatus.setLineSpacing(dp(2), 1f);
        body.addView(tvStatus, lp(12));

        // 主按钮
        btnMain = primary("登录");
        btnMain.setOnClickListener(v -> submit());
        body.addView(btnMain, lp(10));

        // 切换登录/注册
        btnSwitch = new TextView(act);
        btnSwitch.setText("没有账号？点这里注册");
        btnSwitch.setTextSize(13);
        btnSwitch.setTextColor(C_TEAL);
        btnSwitch.setGravity(Gravity.CENTER);
        btnSwitch.setPadding(dp(8), dp(14), dp(8), dp(14));
        btnSwitch.setOnClickListener(v -> {
            registering = !registering;
            tvStatus.setText("");
            btnMain.setText(registering ? "注册并登录" : "登录");
            btnSwitch.setText(registering ? "已有账号？点这里登录" : "没有账号？点这里注册");
        });
        body.addView(btnSwitch, lp(4));

        // 忘记密码说明
        TextView foot = new TextView(act);
        foot.setText("忘记密码：找管理员重置。"
                + "\n数据只在你和设备之间传输，不上报任何第三方。");
        foot.setTextSize(12);
        foot.setTextColor(C_FG3);
        foot.setLineSpacing(dp(3), 1f);
        foot.setPadding(dp(2), dp(18), dp(2), dp(4));
        body.addView(foot);

        dlg.setContentView(root);
    }

    private LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    private TextView label(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(C_FG3);
        return t;
    }

    private EditText field(String hint, boolean pwd) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setHintTextColor(C_FG3);
        e.setTextColor(C_FG);
        e.setTextSize(15);
        e.setBackground(round(C_BG2));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (!pwd) e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private TextView primary(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(15);
        t.setTextColor(0xFF10131A);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(C_TEAL));
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        return t;
    }

    // ── 提交 ──────────────────────────────────────────────

    private void submit() {
        if (busy) return;
        final String name = etName.getText().toString().trim();
        final String pwd = etPwd.getText().toString();

        if (name.isEmpty()) { tvStatus.setText("用户名不能为空"); return; }
        if (name.length() > 20) { tvStatus.setText("用户名最长 20 个字"); return; }
        if (pwd.length() < 4) { tvStatus.setText("密码至少 4 位"); return; }

        busy = true;
        tvStatus.setTextColor(C_FG3);
        tvStatus.setText(registering ? "正在注册…" : "正在登录…");
        btnMain.setAlpha(0.5f);

        final boolean asRegister = registering;
        new Thread(() -> {
            Api.R r = asRegister ? Api.register(name, pwd) : Api.login(name, pwd);
            act.runOnUiThread(() -> {
                busy = false;
                btnMain.setAlpha(1f);
                if (!r.ok) {
                    tvStatus.setTextColor(C_RED);
                    tvStatus.setText(r.msg.isEmpty() ? "失败了，再试一次" : r.msg);
                    return;
                }
                JSONObject b = r.body;
                Account acc = new Account();
                acc.uid = b.optString("uid", "");
                acc.username = b.optString("username", name);
                acc.token = b.optString("token", "");
                if (!acc.loggedIn()) {
                    tvStatus.setTextColor(C_RED);
                    tvStatus.setText("服务器返回的凭证不完整，稍后再试");
                    return;
                }
                acc.save(act);
                Account.setLastSyncedAt(act, 0);
                dlg.dismiss();
                if (onLoggedIn != null) onLoggedIn.onLoggedIn(acc);
            });
        }).start();
    }
}