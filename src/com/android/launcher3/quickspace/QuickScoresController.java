/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.quickspace;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceManager;
import android.app.smartspace.SmartspaceSession;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.app.smartspace.uitemplatedata.HeadToHeadTemplateData;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live game scores for At a glance, from Google's At a Glance on the home screen surface, the one
 * the Pixel launcher reads. Google only sends games of the teams followed in the Google app. The
 * cards are read the same way as the lock screen's Now Bar reads them.
 */
public class QuickScoresController {

  private static final String TAG = "QuickScores";
  private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
  private static final String UI_SURFACE = "home";
  private static final String AOD_SUFFIX = "_aod";
  private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
  private static final Pattern NAME_SCORE = Pattern.compile("^(\\D{2,40}?)\\s+(\\d{1,3})$");
  private static final Pattern SCORE = Pattern.compile("^\\d{1,3}$");
  private static final Pattern FINAL = Pattern.compile(
      "\\b(final|ft|full[ -]?time|ended|aet)\\b", Pattern.CASE_INSENSITIVE);
  /** Stands in for a logo in the text; a span draws the logo over it. */
  private static final String LOGO = "￼";

  /** One game. The scores are empty before it starts. */
  public static final class Score {
    public final String key;
    public final String team1;
    public final String team2;
    public final String score1;
    public final String score2;
    public final Drawable logo1;
    public final Drawable logo2;
    /** The clock, the period or the state of the game, as Google writes it. */
    public final String status;
    private final Intent mIntent;
    private final PendingIntent mPendingIntent;

    Score(String key, String team1, String team2, String score1, String score2, Drawable logo1,
        Drawable logo2, String status, Intent intent, PendingIntent pendingIntent) {
      this.key = key;
      this.team1 = team1;
      this.team2 = team2;
      this.score1 = score1;
      this.score2 = score2;
      this.logo1 = logo1;
      this.logo2 = logo2;
      this.status = TextUtils.isEmpty(status) ? null : status;
      mIntent = intent;
      mPendingIntent = pendingIntent;
    }

    public boolean isStarted() {
      return !score1.isEmpty();
    }

    /** Under way: scores up and no final whistle in the status. */
    public boolean isLive() {
      return isStarted() && (status == null || !FINAL.matcher(status).find());
    }

    /** "LAL 95 – 70 PHX · Q3 - 03:07", or "LAL vs PHX · 5:20 PM" before the game. */
    public String text(Context context) {
      return join(versus(context, team1, team2), status);
    }

    /**
     * The teams and the score with each team's logo beside its name, drawn at the text's size,
     * so any TextView can show it. {@code upper} capitalizes it for the styles set in capitals.
     */
    public SpannableStringBuilder line(Context context, boolean upper, Locale locale) {
      SpannableStringBuilder text = new SpannableStringBuilder();
      appendLogo(text, logo1);
      text.append(versus(context, upper(team1, upper, locale), upper(team2, upper, locale)));
      appendLogo(text, logo2);
      return text;
    }

    /** {@link #line} and then the status. */
    public CharSequence text(Context context, boolean upper, Locale locale) {
      SpannableStringBuilder text = line(context, upper, locale);
      if (status != null) text.append(" · ").append(upper(status, upper, locale));
      return text;
    }

    /** Whatever changes while a game goes on, to tell one moment from the next. */
    public String signature() {
      return key + '|' + score1 + ':' + score2 + '|' + status;
    }

    /** True when this is the same game with a different score: someone just scored. */
    public boolean scoredSince(Score before) {
      return before != null && key.equals(before.key) && isStarted() && before.isStarted()
          && !(score1.equals(before.score1) && score2.equals(before.score2));
    }

    private String versus(Context context, String first, String second) {
      return isStarted()
          ? first + " " + score1 + " – " + score2 + " " + second
          : context.getString(R.string.glance_score_versus, first, second);
    }

    private static void appendLogo(SpannableStringBuilder text, Drawable logo) {
      if (logo == null) return;
      boolean after = text.length() > 0;
      if (after) text.append(' ');
      int start = text.length();
      text.append(LOGO);
      text.setSpan(new LogoSpan(logo), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      if (!after) text.append(' ');
    }

    private static String upper(String text, boolean upper, Locale locale) {
      return upper ? text.toUpperCase(locale) : text;
    }

    /** Opens the game the way the card does: Google's page for it. */
    public void open(View view) {
      Context context = view.getContext();
      if (mIntent == null && mPendingIntent != null) {
        try {
          // The launcher is in front; let the card's own intent open over it.
          mPendingIntent.send(context, 0, null, null, null, null,
              ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                  ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle());
          return;
        } catch (PendingIntent.CanceledException e) {
          // Fall back to a search.
        }
      }
      Intent intent = mIntent != null ? new Intent(mIntent)
          : new Intent(Intent.ACTION_WEB_SEARCH)
              .putExtra(SearchManager.QUERY, team1 + " vs " + team2);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      try {
        context.startActivity(intent);
      } catch (ActivityNotFoundException | SecurityException e) {
        Log.w(TAG, "Nothing opens the game", e);
      }
    }

    private static String join(String first, String second) {
      if (TextUtils.isEmpty(second)) return first;
      return first + " · " + second;
    }
  }

  /** A team's logo in a line of text, as tall as the text. */
  private static final class LogoSpan extends ReplacementSpan {
    private final Drawable mLogo;

    LogoSpan(Drawable logo) {
      mLogo = logo;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
        Paint.FontMetricsInt fm) {
      Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
      if (fm != null) {
        fm.ascent = metrics.ascent;
        fm.descent = metrics.descent;
        fm.top = metrics.top;
        fm.bottom = metrics.bottom;
      }
      return metrics.descent - metrics.ascent;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
        int y, int bottom, Paint paint) {
      Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
      int size = metrics.descent - metrics.ascent;
      int left = Math.round(x);
      int logoTop = y + metrics.ascent;
      mLogo.setBounds(left, logoTop, left + size, logoTop + size);
      mLogo.draw(canvas);
    }
  }

  private final Context mContext;
  private final Runnable mOnChanged;
  private SmartspaceSession mSession;
  private boolean mShown;
  private List<Score> mScores = Collections.emptyList();
  /** Last card read per target, by a hash of its RemoteViews. Touched on the helper thread only. */
  private final Map<String, Pair<Integer, Score>> mCards = new HashMap<>();

  private final SmartspaceSession.OnTargetsAvailableListener mTargetsListener = targets -> {
    SmartspaceSession from = mSession;
    if (from == null || !mShown) return;
    if (DEBUG) {
      StringBuilder ids = new StringBuilder();
      for (SmartspaceTarget t : targets) {
        ids.append(t.getFeatureType()).append(':').append(t.getSmartspaceTargetId()).append(' ');
      }
      Log.d(TAG, "At a Glance sent " + ids);
    }
    UI_HELPER_EXECUTOR.execute(() -> {
      List<Score> list = read(targets);
      MAIN_EXECUTOR.execute(() -> {
        if (mSession == from) set(list);
      });
    });
  };

  /** {@code onChanged} runs on the main thread whenever the games change. */
  public QuickScoresController(Context context, Runnable onChanged) {
    mContext = context.getApplicationContext();
    mOnChanged = onChanged;
  }

  public static boolean isEnabled(Context context) {
    return LauncherPrefs.SHOW_QUICKSPACE_SCORES.get(context);
  }

  /** Opens the session once something on screen shows At a glance. */
  public void start() {
    if (mSession != null || !isEnabled(mContext)) return;
    SmartspaceManager manager = mContext.getSystemService(SmartspaceManager.class);
    if (manager == null) return;
    try {
      mSession = manager.createSmartspaceSession(
          new SmartspaceConfig.Builder(mContext, UI_SURFACE).setSmartspaceTargetCount(5).build());
      mSession.addOnTargetsAvailableListener(MAIN_EXECUTOR, mTargetsListener);
      // At a Glance only fills a surface it has been told is showing.
      if (mShown) notifyShown();
    } catch (RuntimeException e) {
      Log.w(TAG, "No At a Glance session", e);
      mSession = null;
    }
  }

  public void stop() {
    SmartspaceSession session = mSession;
    mSession = null;
    if (session != null) {
      try {
        if (mShown) {
          session.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(
              SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN).build());
        }
        session.removeOnTargetsAvailableListener(mTargetsListener);
        session.close();
      } catch (RuntimeException e) {
        // Gone with the service.
      }
    }
    set(Collections.emptyList());
  }

  /** The home screen came into view or went away. */
  public void setShown(boolean shown) {
    if (mShown == shown) return;
    mShown = shown;
    if (mSession != null) notifyShown();
  }

  private void notifyShown() {
    try {
      mSession.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(mShown
          ? SmartspaceTargetEvent.EVENT_UI_SURFACE_SHOWN
          : SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN).build());
      if (mShown) mSession.requestSmartspaceUpdate();
    } catch (RuntimeException e) {
      Log.w(TAG, "At a Glance session gone", e);
    }
  }

  /** The game to show: one under way first, then one about to start or just over. */
  public Score getScore() {
    for (Score score : mScores) {
      if (score.isLive()) return score;
    }
    return mScores.isEmpty() ? null : mScores.get(0);
  }

  private void set(List<Score> list) {
    if (list.isEmpty() && mScores.isEmpty()) return;
    mScores = list;
    mOnChanged.run();
  }

  // Reading the cards, on the helper thread.

  private List<Score> read(List<SmartspaceTarget> targets) {
    List<Score> list = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (SmartspaceTarget target : targets) {
      ids.add(target.getSmartspaceTargetId());
      Score score = readTarget(target);
      if (score != null) list.add(score);
    }
    mCards.keySet().retainAll(ids);
    return list;
  }

  private Score readTarget(SmartspaceTarget target) {
    if (target.getFeatureType() != SmartspaceTarget.FEATURE_SPORTS) return null;
    // A card can come twice; the copy for the always-on display has no logos.
    if (target.getSmartspaceTargetId().endsWith(AOD_SUFFIX)) return null;
    SmartspaceAction action = target.getHeaderAction() != null
        ? target.getHeaderAction() : target.getBaseAction();
    if (target.getTemplateData() instanceof HeadToHeadTemplateData) {
      return headToHead(target, (HeadToHeadTemplateData) target.getTemplateData(), action);
    }
    Score score = extrasScore(target, action);
    return score != null ? score : cardScore(target, action);
  }

  private Score headToHead(SmartspaceTarget target, HeadToHeadTemplateData h2h,
      SmartspaceAction action) {
    if (h2h.getHeadToHeadFirstCompetitorText() == null
        || h2h.getHeadToHeadSecondCompetitorText() == null) {
      return null;
    }
    String first = String.valueOf(h2h.getHeadToHeadFirstCompetitorText().getText()).trim();
    String second = String.valueOf(h2h.getHeadToHeadSecondCompetitorText().getText()).trim();
    CharSequence status = h2h.getHeadToHeadTitle() != null
        ? h2h.getHeadToHeadTitle().getText()
        : target.getHeaderAction() != null ? target.getHeaderAction().getSubtitle() : null;
    Drawable logo1 = h2h.getHeadToHeadFirstCompetitorIcon() != null
        ? h2h.getHeadToHeadFirstCompetitorIcon().getIcon().loadDrawable(mContext) : null;
    Drawable logo2 = h2h.getHeadToHeadSecondCompetitorIcon() != null
        ? h2h.getHeadToHeadSecondCompetitorIcon().getIcon().loadDrawable(mContext) : null;
    Matcher m1 = NAME_SCORE.matcher(first);
    Matcher m2 = NAME_SCORE.matcher(second);
    if (m1.matches() && m2.matches()) {
      return score(target, m1.group(1), m2.group(1), m1.group(2), m2.group(2), logo1, logo2,
          status, action);
    }
    // Before the game: names only.
    return score(target, first, second, "", "", logo1, logo2, status, action);
  }

  private Score extrasScore(SmartspaceTarget target, SmartspaceAction action) {
    for (SmartspaceAction a : new SmartspaceAction[] {target.getHeaderAction(),
        target.getBaseAction()}) {
      Bundle extras = a == null ? null : a.getExtras();
      if (extras == null) continue;
      String team1 = extras.getString("firstCompetitorName");
      String team2 = extras.getString("secondCompetitorName");
      if (team1 == null || team2 == null) continue;
      return score(target, team1, team2, extras.getString("firstCompetitorScore", ""),
          extras.getString("secondCompetitorScore", ""),
          logo(extras.getParcelable("firstCompetitorLogo", Bitmap.class)),
          logo(extras.getParcelable("secondCompetitorLogo", Bitmap.class)),
          extras.getString("matchTimeSummary",
              a.getSubtitle() == null ? null : a.getSubtitle().toString()), action);
    }
    return null;
  }

  private Score cardScore(SmartspaceTarget target, SmartspaceAction action) {
    RemoteViews views = target.getRemoteViews();
    if (views == null) return null;
    String id = target.getSmartspaceTargetId();
    Integer hash = hashOf(views);
    Pair<Integer, Score> last = mCards.get(id);
    if (last != null && hash != null && hash.equals(last.first)) return last.second;
    Score score = readCard(target, views, action);
    if (hash != null) mCards.put(id, Pair.create(hash, score));
    return score;
  }

  private static Integer hashOf(RemoteViews views) {
    Parcel parcel = Parcel.obtain();
    try {
      parcel.pushAllowFds(false);
      views.writeToParcel(parcel, 0);
      return java.util.Arrays.hashCode(parcel.marshall());
    } catch (RuntimeException e) {
      return null;
    } finally {
      parcel.recycle();
    }
  }

  /**
   * Google sends the Pixel card as RemoteViews with empty actions and no template. Read it the
   * way the eye does: league, first team (a logo whose description is its abbreviation), its
   * score, the clock, the other score, the second team. No scores before kick-off.
   */
  private Score readCard(SmartspaceTarget target, RemoteViews views, SmartspaceAction action) {
    View root;
    try {
      root = views.apply(mContext, new FrameLayout(mContext));
    } catch (RuntimeException e) {
      Log.w(TAG, "Card would not inflate", e);
      return null;
    }
    List<String> texts = new ArrayList<>();
    LinkedHashMap<String, Drawable> logos = new LinkedHashMap<>();
    walk(root, texts, logos);
    if (DEBUG) Log.d(TAG, "Card texts=" + texts + " logos=" + logos.keySet());
    // Buttons carry descriptions too; the teams' abbreviations are also written out.
    List<String> teams = new ArrayList<>(logos.keySet());
    teams.retainAll(texts);
    if (teams.size() != 2) teams = new ArrayList<>(logos.keySet());
    List<String> scores = new ArrayList<>();
    for (String text : texts) {
      if (SCORE.matcher(text).matches()) scores.add(text);
    }
    if (teams.size() != 2 || (scores.size() != 0 && scores.size() != 2)) return null;
    String league = texts.isEmpty() ? null : texts.get(0);
    if (league != null && (teams.contains(league) || SCORE.matcher(league).matches())) {
      league = null;
    }
    StringBuilder status = new StringBuilder();
    for (String text : texts) {
      if (teams.contains(text) || SCORE.matcher(text).matches() || text.equals(league)) continue;
      if (status.length() > 0) status.append(" · ");
      status.append(text);
    }
    return score(target, teams.get(0), teams.get(1),
        scores.isEmpty() ? "" : scores.get(0), scores.isEmpty() ? "" : scores.get(1),
        logos.get(teams.get(0)), logos.get(teams.get(1)),
        status.length() > 0 ? status : league, action);
  }

  private static void walk(View view, List<String> texts, Map<String, Drawable> logos) {
    if (view.getVisibility() != View.VISIBLE) return;
    if (view instanceof TextView) {
      CharSequence text = ((TextView) view).getText();
      String value = text == null ? "" : text.toString().replaceAll("\\s+", " ").trim();
      if (!value.isEmpty()) texts.add(value);
    } else if (view instanceof ImageView) {
      CharSequence description = view.getContentDescription();
      String team = description == null ? "" : description.toString().trim();
      Drawable logo = ((ImageView) view).getDrawable();
      if (!team.isEmpty() && logo != null) logos.putIfAbsent(team, copyOf(logo, view));
    } else if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), texts, logos);
    }
  }

  /** The card's views are thrown away after reading; keep a logo of our own. */
  private static Drawable copyOf(Drawable logo, View from) {
    if (logo instanceof BitmapDrawable && ((BitmapDrawable) logo).getBitmap() != null) {
      return new BitmapDrawable(from.getResources(), ((BitmapDrawable) logo).getBitmap());
    }
    Drawable.ConstantState state = logo.getConstantState();
    Drawable copy = state != null ? state.newDrawable(from.getResources()) : logo;
    copy.setCallback(null);
    return copy;
  }

  private Score score(SmartspaceTarget target, String team1, String team2, String score1,
      String score2, Drawable logo1, Drawable logo2, CharSequence status,
      SmartspaceAction action) {
    String t1 = team1 == null ? "" : team1.trim();
    String t2 = team2 == null ? "" : team2.trim();
    if (!isTeam(t1) || !isTeam(t2)) return null;
    String s1 = score1 == null ? "" : score1.trim();
    String s2 = score2 == null ? "" : score2.trim();
    // Both scores, or none before the game; never one.
    if (s1.isEmpty() != s2.isEmpty()) return null;
    if (!s1.isEmpty() && (!SCORE.matcher(s1).matches() || !SCORE.matcher(s2).matches())) {
      return null;
    }
    return new Score(target.getSmartspaceTargetId(), t1, t2, s1, s2, logo1, logo2,
        status == null ? null : status.toString().trim(), linkOf(action),
        action == null ? null : action.getPendingIntent());
  }

  /** Only a web link from the card is kept, rebuilt without anything else it carried. */
  private static Intent linkOf(SmartspaceAction action) {
    Intent intent = action == null ? null : action.getIntent();
    Uri data = intent == null ? null : intent.getData();
    if (data == null || !("https".equals(data.getScheme()) || "http".equals(data.getScheme()))) {
      return null;
    }
    Intent link = new Intent(Intent.ACTION_VIEW, data);
    if (GOOGLE_APP.equals(intent.getPackage())) link.setPackage(GOOGLE_APP);
    return link;
  }

  private static boolean isTeam(String name) {
    if (name.length() < 2 || name.length() > 40) return false;
    for (int i = 0; i < name.length(); i++) {
      if (Character.isLetter(name.charAt(i))) return true;
    }
    return false;
  }

  private Drawable logo(Bitmap bitmap) {
    return bitmap == null ? null : new BitmapDrawable(mContext.getResources(), bitmap);
  }
}
