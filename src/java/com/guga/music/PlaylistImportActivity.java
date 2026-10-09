package com.guga.music;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 导入歌单页（入口在收藏页·本地歌单区）：粘贴分享链接 → 解析 → 逐首匹配 B 站 → 确认后存为本地歌单 */
public class PlaylistImportActivity extends Activity {

private EditText etLink, etName;
private LinearLayout llPlatforms, llProgress, llResult, llFail;
private TextView btnStart, tvStage, tvSummary, tvUnmatchedHead, tvUnmatched, btnSave, tvFailMsg;
private String bruteDigits;
private ProgressBar pbImport;
private ListView lvResult;
private ScrollView svUnmatched;
private BiliApi api;

private String selectedPlatform; // null = 自动识别
private boolean manualPick;
private final List<TextView> chips = new ArrayList<>();
private final String[] chipPlatforms = {null, "netease", "qq", "kugou", "kuwo", "bodian", "qishui"};
private final String[] chipLabels = {"自动识别", "网易云", "QQ音乐", "酷狗", "酷我", "波点", "汽水"};

private volatile boolean working, cancelled;
private Thread worker;
private PlaylistImport.Parsed parsed;
private final List<Track> matchedTracks = new ArrayList<>();
private final List<PlaylistImport.Src> matchedSrcs = new ArrayList<>();
private final List<PlaylistImport.Src> unmatched = new ArrayList<>();

@Override protected void onCreate(Bundle saved) {
super.onCreate(saved);
ThemeUtil.apply(this);
setContentView(R.layout.activity_playlist_import);
api = new BiliApi(getApplicationContext());
etLink = findViewById(R.id.etLink);
etName = findViewById(R.id.etName);
llPlatforms = findViewById(R.id.llPlatforms);
llProgress = findViewById(R.id.llProgress);
llResult = findViewById(R.id.llResult);
llFail = findViewById(R.id.llFail);
tvFailMsg = findViewById(R.id.tvFailMsg);
findViewById(R.id.btnRetryFail).setOnClickListener(v -> { llFail.setVisibility(View.GONE); startImport(); });
btnStart = findViewById(R.id.btnStart);
tvStage = findViewById(R.id.tvStage);
tvSummary = findViewById(R.id.tvSummary);
tvUnmatchedHead = findViewById(R.id.tvUnmatchedHead);
tvUnmatched = findViewById(R.id.tvUnmatched);
btnSave = findViewById(R.id.btnSave);
pbImport = findViewById(R.id.pbImport);
lvResult = findViewById(R.id.lvResult);
svUnmatched = findViewById(R.id.svUnmatched);
findViewById(R.id.btnBackImport).setOnClickListener(v -> finish());
buildChips();
Haptics.attachRatchet(lvResult);
Haptics.attachScrollRatchet(svUnmatched);
findViewById(R.id.btnPaste).setOnClickListener(v -> {
try {
ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
ClipData cd = cm == null ? null : cm.getPrimaryClip();
if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
etLink.setText(cd.getItemAt(0).getText().toString());
etLink.setSelection(etLink.getText().length());
// 粘进来就能认出的，直接开跑，少点一下
if (PlaylistImport.detect(etLink.getText().toString()) != null) startImport();
} else toast("剪贴板是空的");
} catch (Exception e) { toast("读取剪贴板失败"); }
});
// 剪贴板里正好有歌单链接时自动填好（刚在别的 App 复制完进来的人不用再点粘贴）
try {
ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
ClipData cd = cm == null ? null : cm.getPrimaryClip();
if (cd != null && cd.getItemCount() > 0 && cd.getItemAt(0).getText() != null) {
String clip = cd.getItemAt(0).getText().toString();
if (PlaylistImport.detect(clip) != null) {
etLink.setText(clip);
toast("已填入剪贴板里的歌单链接，点「开始导入」即可");
}
}
} catch (Exception ignored) {}
handleIntent(getIntent());
etLink.addTextChangedListener(new TextWatcher() {
@Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
@Override public void afterTextChanged(Editable s) {}
@Override public void onTextChanged(CharSequence s, int a, int b, int c) {
if (working) return;
manualPick = false;
PlaylistImport.Detected d = PlaylistImport.detect(s.toString());
selectedPlatform = d == null ? null : d.platform;
styleChips();
}
});
btnStart.setOnClickListener(v -> startImport());
findViewById(R.id.btnCancel).setOnClickListener(v -> { cancelled = true; });
btnSave.setOnClickListener(v -> savePlaylist());
}

/** 从别的 App「分享到咕嘎音乐」进来：文本已带好，直接开跑 */
private void handleIntent(android.content.Intent it) {
if (it == null) return;
String shared = null;
if (android.content.Intent.ACTION_SEND.equals(it.getAction())) {
CharSequence t = it.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
if (t != null) shared = t.toString();
}
if (shared != null && !shared.trim().isEmpty()) {
etLink.setText(shared.trim());
etLink.setSelection(etLink.getText().length());
llFail.setVisibility(View.GONE);
startImport();
}
}

@Override protected void onNewIntent(android.content.Intent it) {
super.onNewIntent(it);
setIntent(it);
handleIntent(it);
}

private void buildChips() {
for (int i = 0; i < chipLabels.length; i++) {
final String p = chipPlatforms[i];
TextView chip = new TextView(this);
chip.setText(chipLabels[i]);
chip.setTextSize(12.5f);
chip.setPadding(dp(13), dp(6), dp(13), dp(6));
LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
if (i > 0) lp.leftMargin = dp(8);
chip.setLayoutParams(lp);
chip.setOnClickListener(v -> {
if (working) return;
manualPick = true;
selectedPlatform = p;
styleChips();
});
chips.add(chip);
llPlatforms.addView(chip);
}
styleChips();
}

private void styleChips() {
for (int i = 0; i < chips.size(); i++) {
boolean sel = chipPlatforms[i] == null ? selectedPlatform == null
: chipPlatforms[i].equals(selectedPlatform);
TextView chip = chips.get(i);
if (sel) {
chip.setBackground(ThemeUtil.accentGradient(this, 15));
chip.setTextColor(ThemeUtil.color(this, R.attr.gOnAccent));
} else {
chip.setBackgroundResource(R.drawable.bg_chip_pill);
chip.setTextColor(ThemeUtil.color(this, R.attr.gTextPri));
}
}
}

private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

private static String extractUrl(String raw) {
Matcher m = Pattern.compile("(https?://[^\\s，。；、）】]+)").matcher(raw == null ? "" : raw);
return m.find() ? m.group(1) : null;
}

private void startImport() {
if (working) return;
String raw = etLink.getText().toString().trim();
if (raw.isEmpty()) { toast("先粘贴歌单链接"); return; }
PlaylistImport.Detected det = PlaylistImport.detect(raw);
if (det == null || det.platform == null || ("kugou".equals(det.platform) && det.id == null)) {
String u = extractUrl(raw);
if (u != null) {
String fin = PlaylistImport.resolveFinalUrl(u);
PlaylistImport.Detected d2 = PlaylistImport.detect(fin);
if (d2 != null && d2.platform != null) det = d2;
}
}
String platform = manualPick || selectedPlatform != null ? selectedPlatform
: (det == null ? null : det.platform);
if (platform == null && det != null) platform = det.platform;
bruteDigits = null;
if (platform == null) {
// 只给了一串数字 ID：不知道哪个平台就挨个试，不用用户猜
String digits = null;
if (raw.matches("\\d{5,}")) digits = raw;
else if (det != null && det.id != null && det.id.matches("\\d{5,}")) digits = det.id;
if (digits == null) {
showFail("😢 没认出这个链接");
return;
}
bruteDigits = digits;
}
PlaylistImport.Detected use = new PlaylistImport.Detected();
use.platform = platform;
use.url = extractUrl(raw);
if (bruteDigits == null) {
if (det != null && platform.equals(det.platform)) {
use.id = det.id;
if (det.url != null) use.url = det.url;
if (det.extra != null) use.extra = det.extra;
} else if (det != null && det.id != null) {
use.id = det.id;
} else if (raw.matches("\\d{5,}")) {
use.id = raw;
}
if ("qishui".equals(platform)) {
if (use.url == null) { showFail("😢 汽水需要完整的分享链接，光有数字ID不够"); return; }
} else if (use.id == null) {
showFail("😢 没找到歌单ID，检查一下链接有没有复制全");
return;
}
}
working = true;
cancelled = false;
matchedTracks.clear(); matchedSrcs.clear(); unmatched.clear(); parsed = null;
llResult.setVisibility(View.GONE);
llFail.setVisibility(View.GONE);
llProgress.setVisibility(View.VISIBLE);
btnStart.setEnabled(false);
btnStart.setText("导入中…");
pbImport.setIndeterminate(true);
tvStage.setText(bruteDigits != null ? "不知道是哪个平台的ID，正在挨个平台试…"
: "正在解析" + PlaylistImport.platformName(platform) + "歌单…");
final PlaylistImport.Detected fu = use;
worker = new Thread(() -> runImport(fu));
worker.start();
}

private void showFail(String msg) {
tvFailMsg.setText(msg);
llFail.setVisibility(View.VISIBLE);
}

private void postFail(String msg) {
runOnUiThread(() -> {
working = false;
llProgress.setVisibility(View.GONE);
btnStart.setEnabled(true);
btnStart.setText("⇩ 开始导入");
showFail("😢 导入失败：" + msg);
});
}

private void runImport(PlaylistImport.Detected use) {
try {
PlaylistImport.Parsed p = bruteDigits != null
? PlaylistImport.fetchByIdBruteforce(bruteDigits) : PlaylistImport.fetch(use);
parsed = p;
if (cancelled) { postReset("已取消"); return; }
int total = p.tracks.size();
runOnUiThread(() -> {
pbImport.setIndeterminate(false);
pbImport.setMax(total);
pbImport.setProgress(0);
tvStage.setText("共 " + total + " 首，开始匹配 B 站…");
});
for (int i = 0; i < total; i++) {
if (cancelled) { postReset("已取消"); return; }
PlaylistImport.Src s = p.tracks.get(i);
Track t = PlaylistImport.matchOne(api, s);
if (t != null) { matchedTracks.add(t); matchedSrcs.add(s); } else unmatched.add(s);
final int done = i + 1, mt = matchedTracks.size();
runOnUiThread(() -> {
pbImport.setProgress(done);
tvStage.setText("匹配中 " + done + "/" + total + " · 已匹配 " + mt + " 首");
});
if (i < total - 1) Thread.sleep(200);
}
runOnUiThread(this::showResult);
} catch (Exception e) {
postFail(e.getMessage() == null ? "网络或链接异常" : e.getMessage());
}
}

private void postReset(String msg) {
runOnUiThread(() -> {
working = false;
llProgress.setVisibility(View.GONE);
btnStart.setEnabled(true);
btnStart.setText("⇩ 开始导入");
if (msg != null) toast(msg);
});
}

private void showResult() {
working = false;
llProgress.setVisibility(View.GONE);
btnStart.setEnabled(true);
btnStart.setText("⇩ 重新导入");
llResult.setVisibility(View.VISIBLE);
etName.setText(parsed.title);
int total = parsed.tracks.size();
tvSummary.setText("来源：" + PlaylistImport.platformName(parsed.platform)
+ " · 共 " + total + " 首 · 匹配 " + matchedTracks.size() + " · 未匹配 " + unmatched.size()
+ (parsed.skipped > 0 ? " · 跳过失效 " + parsed.skipped : ""));
lvResult.setAdapter(new BaseAdapter() {
@Override public int getCount() { return matchedTracks.size(); }
@Override public Object getItem(int pos) { return matchedTracks.get(pos); }
@Override public long getItemId(int pos) { return pos; }
@Override public View getView(int pos, View cv, ViewGroup parent) {
if (cv == null) cv = LayoutInflater.from(PlaylistImportActivity.this)
.inflate(R.layout.row_import_result, parent, false);
Track t = matchedTracks.get(pos);
PlaylistImport.Src s = matchedSrcs.get(pos);
ImageView iv = cv.findViewById(R.id.ivImpCover);
TextView tvT = cv.findViewById(R.id.tvImpTitle);
TextView tvS = cv.findViewById(R.id.tvImpSub);
tvT.setText(t.title);
tvS.setText(t.author + " · 原曲：" + s.name + (s.artist.isEmpty() ? "" : " - " + s.artist));
iv.setImageBitmap(null);
if (t.cover != null && !t.cover.isEmpty()) ImgLoader.load(iv, t.cover);
return cv;
}
});
if (unmatched.isEmpty()) {
tvUnmatchedHead.setVisibility(View.GONE);
svUnmatched.setVisibility(View.GONE);
} else {
tvUnmatchedHead.setVisibility(View.VISIBLE);
tvUnmatchedHead.setText("未匹配（" + unmatched.size() + " 首，不会导入）");
StringBuilder sb = new StringBuilder();
for (PlaylistImport.Src s : unmatched) {
sb.append("· ").append(s.name);
if (!s.artist.isEmpty()) sb.append(" - ").append(s.artist);
sb.append("\n");
}
tvUnmatched.setText(sb.toString());
svUnmatched.setVisibility(View.VISIBLE);
}
btnSave.setVisibility(matchedTracks.isEmpty() ? View.GONE : View.VISIBLE);
if (matchedTracks.isEmpty()) toast("一首也没匹配上，换个歌单或检查网络再试");
}

private void savePlaylist() {
String name = etName.getText().toString().trim();
if (name.isEmpty()) name = parsed == null ? "导入的歌单" : parsed.title;
LocalDb db = new LocalDb(this);
long pid = db.createPlaylist(name);
int ok = 0;
for (Track t : matchedTracks) if (db.addTrack(pid, t)) ok++;
toast("已保存「" + name + "」（" + ok + " 首），去收藏页·本地歌单看");
finish();
}

@Override protected void onDestroy() {
cancelled = true;
super.onDestroy();
}
}
