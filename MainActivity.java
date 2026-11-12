package com.reelog.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.util.Size;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.*;
import androidx.webkit.WebViewAssetLoader;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
  static final String HOST = "appassets.androidplatform.net";
  WebView web;
  WebViewAssetLoader loader;
  ValueCallback<Uri[]> fileCb;

  String perm() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE; }
  boolean granted() { return checkSelfPermission(perm()) == PackageManager.PERMISSION_GRANTED; }
  void js(boolean b) { web.evaluateJavascript("window.__vcb&&window.__vcb(" + b + ")", null); }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    web = new WebView(this);
    setContentView(web);
    loader = new WebViewAssetLoader.Builder().setDomain(HOST)
        .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    web.addJavascriptInterface(new Bridge(), "AndroidBridge");
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if (HOST.equals(u.getHost())) {
          String p = u.getPath();
          if (p != null && p.startsWith("/video/")) return video(r, p.substring(7));
          if (p != null && p.startsWith("/thumb/")) return thumb(p.substring(7));
          return loader.shouldInterceptRequest(u);
        }
        return null;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = cb;
        try { startActivityForResult(p.createIntent(), 7); } catch (Exception e) { fileCb = null; return false; }
        return true;
      }
    });
    web.loadUrl("https://" + HOST + "/assets/index.html");
  }

  @Override protected void onActivityResult(int rq, int rs, Intent d) {
    if (rq == 7 && fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(rs, d)); fileCb = null; }
  }

  @Override public void onRequestPermissionsResult(int rq, String[] p, int[] g) { js(granted()); }

  class Bridge {
    @JavascriptInterface public boolean hasAccess() { return granted(); }
    @JavascriptInterface public void request() {
      runOnUiThread(() -> { if (granted()) js(true); else requestPermissions(new String[]{perm()}, 1); });
    }
    @JavascriptInterface public void openSettings() {
      runOnUiThread(() -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
    }
    @JavascriptInterface public String list() {
      JSONArray a = new JSONArray();
      if (!granted()) return a.toString();
      boolean q = Build.VERSION.SDK_INT >= 29;
      String[] pr = q
        ? new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.OWNER_PACKAGE_NAME}
        : new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
      String sel = MediaStore.Video.Media.DURATION + " >= 300000";
      try (Cursor c = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pr, sel, null, MediaStore.Video.Media.DISPLAY_NAME + " ASC")) {
        while (c != null && c.moveToNext() && a.length() < 5000) {
          if (skip(c.getString(1), c.getString(4), q ? c.getString(5) : "", q ? c.getString(6) : "")) continue;
          JSONObject o = new JSONObject();
          o.put("name", c.getString(1));
          o.put("url", "https://" + HOST + "/video/" + c.getLong(0));
          o.put("size", c.getLong(2));
          o.put("dur", c.getLong(3));
          o.put("thumb", "https://" + HOST + "/thumb/" + c.getLong(0));
          a.put(o);
        }
      } catch (Exception e) { }
      return a.toString();
    }
  }

  // فقط فیلم و سریال: دوربین، اسکرین‌رکورد، اسکرین‌شات، دانلودهای تلگرام/کروم، ادیتورها (اینشات و...) و ویدیوهای شبکه‌های اجتماعی حذف می‌شوند
  static final String[] BAD_PKG = {"telegram", "challegram", "chrome", "android.providers.downloads",
      "camerasideas", "inshot", "lemon.lv", "capcut", "kinemaster", "videoleap", "lightricks", "filmora", "wondershare",
      "frontrow", "cyberlink", "quvideo", "xiaoying", "alightcreative", "picsart", "videoshow", "magisto", "movavi", "vimosoft", "splice.video"};
  // کلمه‌هایی که در اسم فایل هم حذف می‌شوند
  static final String[] BAD_ANY = {"screenrecord", "screen record", "screen_record", "screen-record", "screenshot",
      "inshot", "capcut", "kinemaster", "videoleap", "filmora", "powerdirector", "youcut", "videoshow", "vllo", "alight motion", "alightmotion"};
  // کلمه‌هایی که فقط در نام پوشه حذف می‌شوند (تا اسم فیلم‌هایی مثل Signal یا Camera اشتباهی حذف نشوند)
  static final String[] BAD_DIR = {"camera", "telegram", "whatsapp", "instagram", "snapchat", "tiktok", "twitter", "facebook", "signal",
      "video editor", "videoeditor", "video_editor", "video maker", "videomaker", "vn editor", "vneditor"};

  static boolean skip(String n, String b, String p, String owner) {
    String nn = n == null ? "" : n.toLowerCase(Locale.ROOT);
    String bb = b == null ? "" : b.toLowerCase(Locale.ROOT);
    String pp = p == null ? "" : p.toLowerCase(Locale.ROOT);
    String ow = owner == null ? "" : owner.toLowerCase(Locale.ROOT);
    String dir = bb + "|" + pp, all = nn + "|" + dir;
    if (pp.startsWith("dcim/") || nn.startsWith("vid_") || nn.startsWith("pxl_") || nn.startsWith("screen")) return true;
    if (pp.startsWith("download/") || pp.startsWith("downloads/") || bb.equals("download") || bb.equals("downloads")) return true;
    for (String t : BAD_ANY) if (all.contains(t)) return true;
    for (String t : BAD_DIR) if (dir.contains(t)) return true;
    for (String t : BAD_PKG) if (ow.contains(t)) return true;
    return false;
  }

  static class Limited extends FilterInputStream {
    long left;
    Limited(InputStream in, long n) { super(in); left = n; }
    @Override public int read() throws IOException { if (left <= 0) return -1; int r = super.read(); if (r >= 0) left--; return r; }
    @Override public int read(byte[] b, int o, int l) throws IOException {
      if (left <= 0) return -1;
      int r = super.read(b, o, (int) Math.min(l, left));
      if (r > 0) left -= r;
      return r;
    }
  }

  WebResourceResponse thumb(String id) {
    try {
      Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
      Bitmap bm;
      if (Build.VERSION.SDK_INT >= 29) bm = getContentResolver().loadThumbnail(uri, new Size(320, 180), null);
      else bm = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), Long.parseLong(id), MediaStore.Video.Thumbnails.MINI_KIND, null);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      bm.compress(Bitmap.CompressFormat.JPEG, 80, o);
      Map<String, String> h = new HashMap<>();
      h.put("Cache-Control", "max-age=86400");
      return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(o.toByteArray()));
    } catch (Exception e) {
      return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }
  }

  WebResourceResponse video(WebResourceRequest r, String id) {
    try {
      Uri uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
      ContentResolver cr = getContentResolver();
      AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r");
      long len = fd.getLength();
      if (len < 0) len = fd.getParcelFileDescriptor().getStatSize();
      String mime = cr.getType(uri);
      if (mime == null) mime = "video/mp4";
      long start = 0, end = len - 1;
      String rg = r.getRequestHeaders().get("Range");
      if (rg != null && rg.startsWith("bytes=")) {
        String[] p = rg.substring(6).split("-", 2);
        start = Long.parseLong(p[0]);
        if (p.length > 1 && !p[1].isEmpty()) end = Long.parseLong(p[1]);
      }
      if (end >= len) end = len - 1;
      FileInputStream in = new FileInputStream(fd.getFileDescriptor());
      in.getChannel().position(fd.getStartOffset() + start);
      Map<String, String> h = new HashMap<>();
      h.put("Accept-Ranges", "bytes");
      h.put("Content-Length", String.valueOf(end - start + 1));
      h.put("Access-Control-Allow-Origin", "*");
      boolean part = rg != null;
      if (part) h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
      return new WebResourceResponse(mime, null, part ? 206 : 200, part ? "Partial Content" : "OK", h, new Limited(in, end - start + 1));
    } catch (Exception e) {
      return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }
  }
}
