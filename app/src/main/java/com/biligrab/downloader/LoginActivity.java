package com.biligrab.downloader;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.TextView;

import com.biligrab.downloader.ui.GlassMeshDrawable;
import com.biligrab.downloader.ui.HyperTheme;

import org.json.JSONObject;

/**
 * 应用内登录 —— 把 B 站官方登录页装进 WebView，登录成功后取回 Cookie。
 *
 * <h3>为什么是网页，而不是自己画一个账号密码表单</h3>
 *
 * <p>自己实现账号密码登录是走不通的。实测 {@code POST
 * passport.bilibili.com/x/passport-login/web/login} 在只有用户名和密码时
 * 直接返回 <b>{@code -105 验证码错误}</b>：服务端要求同时带上极验的
 * {@code challenge} / {@code validate} / {@code seccode} 三元组，
 * 而这三样只能由极验前端算出来。不接打码服务就没有合法途径拿到它们。</p>
 *
 * <p>原项目 {@code 1250422131/bilibilias} 也没做账号密码登录 ——
 * 它的登录只有两种：扫码（{@code x/passport-login/web/qrcode/*}
 * 与 {@code x/passport-tv-login/qrcode/*}）和粘贴 Cookie。</p>
 *
 * <p>载入官方登录页就绕开了整个问题：验证码由 B 站自己的页面处理，
 * 而且账号密码、短信验证码、扫码三种方式一个不少。</p>
 *
 * <h3>为什么不能只靠 onPageFinished</h3>
 *
 * <p>那个登录页是个单页应用，登录成功之后是以 XHR 完成的，<b>不一定触发页面跳转</b>，
 * 也就不会回调 {@code onPageFinished}。所以除了页面加载完成时查一次，
 * 还按固定间隔轮询 Cookie —— 轮询才是真正管用的那条路。</p>
 */
public class LoginActivity extends Activity {

    private static final String TAG = "BiliGrab/Login";

    /** 官方登录页。它是单页应用，内容由 JS 渲染，所以必须在 WebView 里跑。 */
    private static final String LOGIN_URL = "https://passport.bilibili.com/login";

    /**
     * 用来判断登录是否完成的地址。
     *
     * <p>SESSDATA 是种在 {@code .bilibili.com} 上的，两个域都能读到；
     * 查询时把两个都问一遍，是因为 CookieManager 是按「这个 URL 能不能看到」
     * 来返回的，跨子域的 cookie 在个别实现上会有出入。</p>
     */
    private static final String[] COOKIE_URLS = {
            "https://www.bilibili.com",
            "https://passport.bilibili.com",
    };

    /** 轮询间隔。太密会让 WebView 一直在忙，太疏用户会觉得「点了没反应」。 */
    private static final long POLL_MS = 900L;

    /** 轮询总时长上限。超过了就停，但不关页面 —— 用户可能只是扫得慢。 */
    private static final long POLL_LIMIT_MS = 10 * 60 * 1000L;

    /**
     * 用一个常见的移动版 Chrome UA。
     *
     * <p>不要用应用自己的 UA：登录页会按 UA 决定给哪套前端，未知 UA 有可能
     * 落到一个被阉割的版本上。这里要的是「和手机浏览器一样」。</p>
     */
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; PDPM00) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private WebView web;
    private TextView tvHint;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 已经取到并保存过登录态。轮询与页面回调都会碰到它，必须防重入。 */
    private boolean saved;
    /** 正在后台校验 Cookie。 */
    private boolean verifying;
    /**
     * 已经记录过 Cookie 罐的目录。
     *
     * <p>它唯一的作用是让「抓不到 Cookie」这件事可诊断：轮询期间什么都读不到时，
     * 日志里至少有一行说明 WebView 的 Cookie 罐是不是空的。
     * <b>只记名字，绝不记值</b> —— SESSDATA 本身就是密码等价物，
     * 写进 logcat 等于把账号摊在别人面前（logcat 别的应用读得到）。</p>
     */
    private boolean loggedNames;
    private long startedAt;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (saved) {
                return;
            }
            if (System.currentTimeMillis() - startedAt > POLL_LIMIT_MS) {
                Log.i(TAG, "轮询到达时限，停止检测（页面保持打开）");
                return;
            }
            checkCookies();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyThemeOverride();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        applyLoginTheme();
        startedAt = System.currentTimeMillis();

        tvHint = findViewById(R.id.tvLoginHint);
        tvHint.setTextColor(HyperTheme.textSecondary(this));
        web = findViewById(R.id.webLogin);

        // 圆角。WebView 自己不会裁背景，得让父级按 outline 裁。
        web.setClipToOutline(true);

        // 登录页要写 localStorage / sessionStorage 存放流程状态，
        // 关掉 DOM storage 会让它在半路上卡住。
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(UA);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // 缩放控件在登录页上只会碍事：它会浮在表单上方
        s.setDisplayZoomControls(false);
        s.setBuiltInZoomControls(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        // 登录流程会跨 passport.bilibili.com 与 www.bilibili.com，
        // 不放开第三方 cookie 时 SESSDATA 有可能拿不到
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u == null ? "" : u.getScheme();
                // 登录页里有「打开 App」之类的按钮，点了会跳 bilibili:// 或 intent://。
                // 那些交给系统会离开本应用，登录也就断了 —— 直接拦下来。
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    Log.i(TAG, "忽略非 http 跳转：" + u);
                    return true;
                }
                // 其余一律留在应用内。登录过程会在好几个域之间跳，
                // 放出去用系统浏览器打开，Cookie 就落到别处了。
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if ("about:blank".equals(url)) {
                    return;
                }
                tvHint.setText(R.string.login_hint);
                web.setBackgroundColor(Color.WHITE);
                checkCookies();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                // 只有主文档失败才值得告诉用户；子资源失败太常见了
                if (!req.isForMainFrame()) {
                    return;
                }
                String desc = err == null ? "" : String.valueOf(err.getDescription());
                Log.w(TAG, "登录页加载失败：" + desc);
                tvHint.setText(getString(R.string.login_page_failed, desc));
            }
        });

        ImageButton close = findViewById(R.id.btnCloseLogin);
        close.setOnClickListener(v -> finish());

        web.loadUrl(LOGIN_URL);
        handler.postDelayed(poller, POLL_MS);
    }

    /**
     * 看看 Cookie 里有没有 SESSDATA。有就交给后台线程去校验。
     *
     * <p>只在这里「发现」，真正的判定放在 {@link #verify}：光凭 Cookie 里有
     * 这个名字不能说明它有效 —— 用户可能只是访问过 B 站。</p>
     */
    private void applyThemeOverride() {
        int mode = Prefs.themeModeStatic(this);
        if (mode == Prefs.THEME_SYSTEM) {
            return;
        }
        Configuration cfg = new Configuration(getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == Prefs.THEME_DARK
                        ? Configuration.UI_MODE_NIGHT_YES
                        : Configuration.UI_MODE_NIGHT_NO);
        try {
            applyOverrideConfiguration(cfg);
        } catch (Throwable ignored) {
            // 资源已被访问时无法覆盖，退回跟随系统，不影响登录。
        }
    }

    private void applyLoginTheme() {
        View root = findViewById(R.id.loginRoot);
        if (root == null) {
            return;
        }
        if (HyperTheme.isGlass(this)) {
            root.setBackground(new GlassMeshDrawable(HyperTheme.isDark(this)));
        } else {
            root.setBackgroundColor(HyperTheme.background(this));
        }
    }
    private void checkCookies() {
        if (saved || verifying || isFinishing()) {
            return;
        }
        String cookie = null;
        CookieManager cm = CookieManager.getInstance();
        for (String u : COOKIE_URLS) {
            String c = cm.getCookie(u);
            if (c == null || c.isEmpty()) {
                continue;
            }
            if (cookie == null) {
                cookie = c;
            } else if (!cookie.contains("SESSDATA=") && c.contains("SESSDATA=")) {
                cookie = c;
            } else {
                // 合并。同一个 cookie 名在两个域上都有时，先出现的说了算 ——
                // 它们是同一份登录态，先后没有实质差别。
                for (String part : c.split(";")) {
                    String name = part.trim().split("=", 2)[0];
                    if (!cookie.contains(name + "=")) {
                        cookie = cookie + "; " + part.trim();
                    }
                }
            }
        }
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        if (!loggedNames) {
            loggedNames = true;
            // 只记名字。这一行是用来区分两种「登录没反应」的：
            // Cookie 罐是空的（WebView 压根没拿到东西）还是
            // 有 Cookie 但没有 SESSDATA（页面还没走完登录）——
            // 两者的排查方向完全相反。
            StringBuilder names = new StringBuilder();
            for (String part : cookie.split(";")) {
                String n = part.trim().split("=", 2)[0];
                if (n.isEmpty()) {
                    continue;
                }
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(n);
            }
            Log.i(TAG, "WebView Cookie 罐：" + (names.length() == 0 ? "(空)" : names.toString()));
        }
        if (!cookie.contains("SESSDATA=")) {
            return;
        }

        verifying = true;
        tvHint.setText(R.string.login_verifying);
        final String toVerify = cookie;
        new Thread(() -> {
            String uname = null;
            String err = null;
            try {
                uname = verify(toVerify);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String fUname = uname;
            final String fErr = err;
            runOnUiThread(() -> {
                verifying = false;
                if (isFinishing() || saved) {
                    return;
                }
                if (fUname != null) {
                    save(toVerify, fUname);
                } else {
                    // 校验没过。多半是还没有真正登录（cookie 里已经有别的名字了），
                    // 那就继续等，不要把错误甩给用户 —— 他可能只是还没扫完。
                    Log.i(TAG, "Cookie 里有 SESSDATA 但校验未通过：" + fErr);
                    tvHint.setText(R.string.login_hint);
                }
            });
        }, "bili-login-verify").start();
    }

    /**
     * 拿 Cookie 去问一次「我是谁」。
     *
     * <p>{@code nav} 是 B 站自己首页用的接口，返回 {@code isLogin} 与用户名。
     * 用它是为了确认这份 Cookie 真的能换到登录态 —— 存一份无效的 SESSDATA
     * 比不登录更糟：界面会显示「已登录」，但画质还是 480P，没法自查。</p>
     *
     * @return 用户名；未登录或无法确认时返回 {@code null}
     */
    private String verify(String cookie) throws Exception {
        String body = Http.get("https://api.bilibili.com/x/web-interface/nav", cookie);
        JSONObject root = new JSONObject(body);
        if (root.optInt("code", -1) != 0) {
            throw new IllegalStateException("nav 返回 " + root.optInt("code") + " " + root.optString("message"));
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null || !data.optBoolean("isLogin", false)) {
            return null;
        }
        String uname = data.optString("uname", "");
        return uname.isEmpty() ? "已登录用户" : uname;
    }

    private void save(String cookie, String uname) {
        saved = true;
        handler.removeCallbacks(poller);

        String sess = extractSessdata(cookie);
        Prefs prefs = new Prefs(this);
        prefs.setSessdata(sess);
        prefs.setLoginUname(uname);

        // CookieManager 默认只在内存里挂着，下次启动就没了。
        // 以后要用其它 cookie（bili_jct 之类）时还得靠它。
        try {
            CookieManager.getInstance().flush();
        } catch (Exception ignored) {
            // 个别 ROM 上 flush 会抛，不影响我们已经存下来的 SESSDATA
        }

        Log.i(TAG, "登录成功：" + uname + "（SESSDATA 长度 " + sess.length() + "）");

        Intent data = new Intent();
        data.putExtra(EXTRA_UNAME, uname);
        setResult(RESULT_OK, data);
        finish();
    }

    /**
     * 从 Cookie 串里挑出 SESSDATA 的值。
     *
     * <p>存的是**值**而不是整串 Cookie，是为了和既有的存储格式保持一致 ——
     * {@code Prefs.sessdata()} 在别处被拼回 {@code SESSDATA=<值>} 使用。</p>
     */
    static String extractSessdata(String cookie) {
        if (cookie == null) {
            return "";
        }
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("SESSDATA=")) {
                return p.substring("SESSDATA=".length()).trim();
            }
        }
        return "";
    }

    /** 登录成功时带回用户名，供调用方直接显示。 */
    public static final String EXTRA_UNAME = "uname";

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poller);
        if (web != null) {
            // 必须先把它从视图树上摘下来再销毁，否则 WebView 会在
            // 已经分离的窗口上继续跑 JS，日志里会刷一屏警告。
            View parent = (View) web.getParent();
            if (parent != null) {
                parent.setVisibility(View.GONE);
            }
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
