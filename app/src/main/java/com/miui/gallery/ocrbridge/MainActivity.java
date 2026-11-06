package com.miui.gallery.ocrbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块说明界面：把功能说明、使用前提与全部 shell 命令直接写进 App 里。
 *
 * 纯 framework API，不引入任何第三方 UI 依赖；深浅色跟随系统。
 * 每条命令都能一键复制。本模块不注册桌面启动图标，界面从
 * LSPosed → 模块 → 长按本模块 →「启动」打开。
 *
 * 注意：本 Activity 只运行在模块自身进程，与相册 / FV 进程里的 Hook 完全隔离，
 * 因此不会影响 Hook 的加载，也不需要在 LSPosed 作用域里勾选模块自己。
 */
public class MainActivity extends Activity {

    private static final String CMD_MAIN =
            "screencap -p | content write --uri content://com.miui.gallery.widget/ocr_input "
                    + "&& content call --uri content://com.miui.gallery.widget "
                    + "--method extract_screen_text";

    private static final String CMD_ADB =
            "adb shell 'screencap -p | content write --uri "
                    + "content://com.miui.gallery.widget/ocr_input && content call "
                    + "--uri content://com.miui.gallery.widget --method extract_screen_text'";

    private static final String CMD_FALLBACK =
            "screencap -p /sdcard/Pictures/.ocr_tmp.png && content call "
                    + "--uri content://com.miui.gallery.widget --method extract_image_text "
                    + "--extra path:s:/sdcard/Pictures/.ocr_tmp.png "
                    + "&& rm /sdcard/Pictures/.ocr_tmp.png";

    private static final String CMD_IMAGE =
            "content call --uri content://com.miui.gallery.widget "
                    + "--method extract_image_text --extra path:s:/sdcard/Pictures/a.png";

    private static final String CMD_PING =
            "content call --uri content://com.miui.gallery.widget --method ping";

    private static final String CMD_TERMUX =
            "su -c 'screencap -p' | content write --uri "
                    + "content://com.miui.gallery.widget/ocr_input && content call "
                    + "--uri content://com.miui.gallery.widget --method extract_screen_text";

    private static final String CMD_BROADCAST =
            "am broadcast -a com.miui.gallery.ocr.action.EXTRACT_SCREEN "
                    + "--es callback com.example.OCR_RESULT";

    private static final String CMD_JAVA =
            "Bundle reply = getContentResolver().call(\n"
                    + "        Uri.parse(\"content://com.miui.gallery.widget\"),\n"
                    + "        \"extract_screen_text\", null, new Bundle());\n"
                    + "String text = reply == null ? \"\" : reply.getString(\"text\", \"\");";

    // 深浅色配色
    private int pageBg, cardBg, codeBg, titleColor, bodyColor, subColor, accent;

    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        initPalette();
        setContentView(R.layout.activity_main);
        container = findViewById(R.id.container);
        container.setBackgroundColor(pageBg);
        getWindow().setStatusBarColor(pageBg);
        buildUi();
    }

    private void initPalette() {
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) {
            pageBg = 0xFF121318;
            cardBg = 0xFF1E2027;
            codeBg = 0xFF12141A;
            titleColor = 0xFFF2F3F7;
            bodyColor = 0xFFC7CBD6;
            subColor = 0xFF8E93A3;
            accent = 0xFF7EA2FF;
        } else {
            pageBg = 0xFFF4F6FB;
            cardBg = 0xFFFFFFFF;
            codeBg = 0xFFF1F3F9;
            titleColor = 0xFF171A22;
            bodyColor = 0xFF454A58;
            subColor = 0xFF7B8191;
            accent = 0xFF3B62E8;
        }
    }

    private void buildUi() {
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            // 版本号拿不到不影响界面
        }

        addHeader(version);
        addCard("模块功能",
                "① 屏幕文字提取：把小米相册的本地 OCR 开放成一条 shell 命令，"
                        + "content call 同步返回识别出的文字，其它 App / Tasker / Termux / adb 都能调用。\n\n"
                        + "② FV 悬浮球接管：让它的「自动文字识别」无感改走小米相册 OCR，"
                        + "不用下载百度语言包、不用给它截屏权限、不需要 root。\n\n"
                        + "③ 内置说明界面：本页可直接复制全部命令，不依赖网络；"
                        + "从 LSPosed 模块页「启动」进入，桌面不占用图标。\n\n"
                        + "识别全程在内存里完成：shell 截屏 → 内存管道 → Bitmap → 相册本地 OCR → 文本，"
                        + "零临时文件。不联网、不上传、不修改系统属性与 Settings 数据库。");

        addCard("使用前提",
                "1. LSPosed 作用域勾选两个：系统相册 com.miui.gallery、"
                        + "FV 悬浮球 com.fooview.android.fooview。只用 shell 命令时勾相册一个即可。\n"
                        + "2. 启用模块后重启手机（或至少强停相册 / FV 悬浮球）。\n"
                        + "3. 建议先在相册里成功用过一次「提取文字」，让本地 OCR 的 so 就绪。\n"
                        + "4. 需要 libxposed 现代 API 102 的框架（LSPosed），基于 compileSdk 35 构建。");

        addCommandCard("① 主命令 · 提取当前屏幕文字（同步返回）",
                "设备上（adb shell / 系统 shell）执行。截屏由 shell 进程完成，PNG 经内存管道"
                        + "直接进入相册进程，零临时文件、不需要 root。",
                CMD_MAIN);
        addCommandCard("从电脑一条命令（adb）", "把整条命令用单引号包起来交给 adb shell。", CMD_ADB);
        addCommandCard("② 兜底 · 临时截图即删（最稳）",
                "content write 在极个别 ROM 不可用时使用。截图写到 /sdcard/Pictures 后立刻读取并删除，"
                        + "不残留；这也是验证 OCR 引擎是否正常的最快方式。",
                CMD_FALLBACK);
        addCommandCard("③ 对已有图片提取文字", "把 path 换成实际图片路径即可。", CMD_IMAGE);
        addCommandCard("④ 查看 OCR 引擎状态（不截屏）",
                "返回 ocrSoLoaded=true 表示相册本地 OCR 就绪，排查时用。",
                CMD_PING);
        addCommandCard("Termux（需 root）",
                "Termux 是 app uid，screencap 会被拒，有 root 时改用 su -c 提权截屏。",
                CMD_TERMUX);
        addCommandCard("⑤ 广播 · 异步（Tasker / MacroDroid / 其它 App）",
                "广播无法同步回传文本；用 --es callback 指定回调 action，"
                        + "也可用 --es out <路径> 写文件、--ez clip true 写剪贴板。",
                CMD_BROADCAST);
        addCommandCard("⑥ 其它 App 直接同步调用（Java，无需 shell / root）",
                "先通过 openOutputStream(ocr_input) 把截图写进内存管道，再 call 取文本；"
                        + "已有图片则改用 extract_image_text 并传 path。",
                CMD_JAVA);

        addCard("返回字段",
                "content call 同步返回 Bundle：\n"
                        + "• success (boolean)：是否成功\n"
                        + "• text (String)：识别出的全文，失败时为空串\n"
                        + "• error (String)：失败原因，仅失败时出现\n"
                        + "• source (String)：screen:pipe（内存管道）/ image:<路径> "
                        + "/ bitmap:app（其它 App 传入）/ ping\n\n"
                        + "示例：Bundle[{text=设置  搜索设置  WLAN ... , success=true, source=screen:pipe}]");

        addCard("FV 悬浮球：自动文字识别无感接管",
                "FV 悬浮球自带的是百度 PaddleLite OCR，需要另下载语言包；小米相册用的是自研 "
                        + "libmiocr.so，随相册内置。两者都吃内存 Bitmap、都吐纯文本，"
                        + "本模块把 FV 已经截好的 Bitmap 跨进程交给相册 OCR，取回文本作为识别结果。\n\n"
                        + "配置好后直接用 FV 原来的「自动文字识别」即可，不需要任何命令。"
                        + "相册未勾作用域 / 引擎未就绪 / 跨进程超时时，会自动回退到 FV 自带引擎，"
                        + "识别功能不会变坏。\n\n"
                        + "日志出现「FV 接管成功」即表示走的是小米相册引擎。");

        addCard("排查",
                "• 执行 ping 看 ocrSoLoaded 是否为 true。\n"
                        + "• 返回 success=false 时看 error 字段：提示「没有收到截图」说明主命令的 "
                        + "content write 那段没执行成功，请改用兜底命令；提示 OCREngine.init 失败，"
                        + "请先在相册里成功用一次「提取文字」。\n"
                        + "• LSPosed 日志或 adb logcat -s GalleryOCR 可看到全链路打点："
                        + "content call 命中 → 管道收到截图 → 送入 OCR → init → doOCR → 文本长度。\n"
                        + "• 改过作用域后务必重启手机。");

        addFooter();
    }

    private void addHeader(String version) {
        TextView title = new TextView(this);
        title.setText("相册文字提取桥");
        title.setTextColor(titleColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(title);

        TextView ver = new TextView(this);
        ver.setText("v" + version + " 正式版  ·  libxposed API 102  ·  LSPosed 模块");
        ver.setTextColor(accent);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        ver.setPadding(0, dp(4), 0, 0);
        container.addView(ver);

        TextView intro = new TextView(this);
        intro.setText("Hook 小米相册本地 OCR，把「提取当前屏幕文字」做成一条 shell 命令；"
                + "并让 FV 悬浮球的自动文字识别无感改走相册 OCR。下方命令可一键复制。");
        intro.setTextColor(bodyColor);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        intro.setLineSpacing(dp(3), 1f);
        intro.setPadding(0, dp(12), 0, dp(4));
        container.addView(intro);
    }

    private void addCard(String title, String body) {
        LinearLayout card = newCard();
        addCardTitle(card, title);
        addCardBody(card, body);
        container.addView(card);
    }

    private void addCommandCard(String title, String desc, String cmd) {
        LinearLayout card = newCard();
        addCardTitle(card, title);
        addCardBody(card, desc);
        addCode(card, cmd);
        addCopyButton(card, cmd);
        container.addView(card);
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(cardBg);
        gd.setCornerRadius(dp(14));
        card.setBackground(gd);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private void addCardTitle(LinearLayout card, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(titleColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tv);
    }

    private void addCardBody(LinearLayout card, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(bodyColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(7);
        tv.setLayoutParams(lp);
        card.addView(tv);
    }

    private void addCode(LinearLayout card, String code) {
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextColor(titleColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(dp(2), 1.1f);
        tv.setTextIsSelectable(true);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(codeBg);
        gd.setCornerRadius(dp(8));
        tv.setBackground(gd);
        tv.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        card.addView(tv);
    }

    private void addCopyButton(LinearLayout card, String cmd) {
        Button b = new Button(this);
        b.setText("复制命令");
        b.setAllCaps(false);
        b.setTextColor(accent);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(codeBg);
        gd.setCornerRadius(dp(8));
        gd.setStroke(dp(1), accent);
        b.setBackground(gd);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(5), dp(14), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END;
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> copy(cmd));
        card.addView(b);
    }

    private void copy(String text) {
        try {
            ClipboardManager cm =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("GalleryOCR", text));
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void addFooter() {
        TextView tv = new TextView(this);
        tv.setText("GalleryOCRBridge · 基于 libxposed 现代 API 102\n"
                + "github.com/by0212zy/GalleryOCRBridge\n\n"
                + "仅用于本机自动化与无障碍辅助等个人用途。识别结果来自小米相册自带的本地 OCR，"
                + "本模块不联网、不上传任何内容。");
        tv.setTextColor(subColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        tv.setLayoutParams(lp);
        container.addView(tv);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
