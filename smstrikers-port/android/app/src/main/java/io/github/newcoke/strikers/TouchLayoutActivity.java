package io.github.newcoke.strikers;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/** The touch control editor: drag, pinch and twist on the overlay, with a toolbar for the selection. */
public class TouchLayoutActivity extends Activity implements OverlayView.EditListener {
    private static final int NAVY = 0xFF0B1A3C, CYAN = 0xFF39C6FF, LABEL = 0xFFC4E9FF;

    private OverlayView overlay;
    private Typeface heavy, bold;
    private FrameLayout root;
    private LinearLayout toolbar, tray;
    private TextView toolbarTitle, sizeValue, angleValue;
    private SeekBar sizeBar;
    private Button hiddenButton;
    private boolean settingSize;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        heavy = OverlayView.labelTypeface(this);
        try {
            bold = getResources().getFont(R.font.barlow_condensed_bold_italic);
        } catch (RuntimeException e) {
            bold = heavy;
        }

        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF16305E, 0xFF081430}));
        root.setFitsSystemWindows(true);

        overlay = new OverlayView(this);
        overlay.applyPreferences(new GamePreferences(this));
        overlay.enableEditing();
        overlay.setEditListener(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = label(getString(R.string.touch_edit_hint), bold, 15, 0xCCFFFFFF);
        hint.setLetterSpacing(0.12f);
        FrameLayout.LayoutParams hintAt = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintAt.bottomMargin = dp(10);
        root.addView(hint, hintAt);

        root.addView(topBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        tray = panel();
        tray.setVisibility(View.GONE);
        FrameLayout.LayoutParams trayAt = new FrameLayout.LayoutParams(dp(330), -2, Gravity.TOP | Gravity.END);
        trayAt.topMargin = dp(64);
        trayAt.rightMargin = dp(16);
        root.addView(tray, trayAt);

        toolbar = toolbar();
        toolbar.setVisibility(View.GONE);
        root.addView(toolbar, new FrameLayout.LayoutParams(dp(290), -2));

        setContentView(root);
        onSelectionChanged();
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(10), dp(16), dp(10));
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xEB050C1C, 0x00050C1C}));
        bar.addView(label(getString(R.string.touch_edit_title), heavy, 28, Color.WHITE));
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Opacity for the whole overlay, here so its effect is visible while setting it.
        final GamePreferences prefs = new GamePreferences(this);
        TextView opacityLabel = rowLabel(R.string.opacity);
        opacityLabel.setMinWidth(0);
        bar.addView(opacityLabel);
        SeekBar opacity = new SeekBar(this);
        opacity.setMax(75);  // 25% to 100%, as in Settings
        opacity.setProgress(Math.max(0, Math.min(75, prefs.getInt(GamePreferences.TOUCH_OPACITY) - 25)));
        opacity.setProgressTintList(ColorStateList.valueOf(CYAN));
        opacity.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        opacity.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A4A80));
        opacity.setContentDescription(getString(R.string.opacity));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                prefs.setInt(GamePreferences.TOUCH_OPACITY, progress + 25);
                overlay.setOpacity((progress + 25) / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        bar.addView(opacity, new LinearLayout.LayoutParams(dp(150), -2));

        hiddenButton = pill("", false);
        hiddenButton.setOnClickListener(v -> {
            tray.setVisibility(tray.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            refreshTray();
        });
        bar.addView(hiddenButton, spaced());
        Button resetAll = pill(getString(R.string.touch_reset_all), false);
        resetAll.setOnClickListener(v -> overlay.resetAll());
        bar.addView(resetAll, spaced());
        Button done = pill(getString(R.string.touch_done), true);
        done.setOnClickListener(v -> finish());
        bar.addView(done, spaced());
        return bar;
    }

    private LinearLayout toolbar() {
        LinearLayout box = panel();
        toolbarTitle = label("", heavy, 22, Color.WHITE);
        box.addView(toolbarTitle);

        LinearLayout sizeRow = row();
        sizeRow.addView(rowLabel(R.string.touch_size));
        sizeBar = new SeekBar(this);
        sizeBar.setMax(150);  // 50% to 200%
        sizeBar.setProgressTintList(ColorStateList.valueOf(CYAN));
        sizeBar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        sizeBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2A4A80));
        sizeBar.setContentDescription(getString(R.string.touch_size));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && !settingSize) overlay.setSelectedScale((progress + 50) / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        sizeRow.addView(sizeBar, new LinearLayout.LayoutParams(0, -2, 1f));
        sizeValue = label("", heavy, 18, Color.WHITE);
        sizeValue.setMinWidth(dp(46));
        sizeValue.setGravity(Gravity.END);
        sizeRow.addView(sizeValue);
        box.addView(sizeRow);

        LinearLayout rotateRow = row();
        rotateRow.addView(rowLabel(R.string.touch_rotate));
        Button left = pill("−15°", false);
        left.setContentDescription(getString(R.string.touch_rotate) + " −15°");
        left.setOnClickListener(v -> overlay.rotateSelected(-15f));
        rotateRow.addView(left);
        angleValue = label("", heavy, 18, Color.WHITE);
        angleValue.setGravity(Gravity.CENTER);
        rotateRow.addView(angleValue, new LinearLayout.LayoutParams(0, -2, 1f));
        Button right = pill("+15°", false);
        right.setContentDescription(getString(R.string.touch_rotate) + " +15°");
        right.setOnClickListener(v -> overlay.rotateSelected(15f));
        rotateRow.addView(right);
        box.addView(rotateRow);

        LinearLayout actions = row();
        Button hide = pill(getString(R.string.touch_hide), false);
        hide.setOnClickListener(v -> overlay.hideSelected());
        actions.addView(hide, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reset = pill(getString(R.string.touch_reset), false);
        reset.setOnClickListener(v -> overlay.resetSelected());
        LinearLayout.LayoutParams resetAt = new LinearLayout.LayoutParams(0, -2, 1f);
        resetAt.leftMargin = dp(10);
        actions.addView(reset, resetAt);
        box.addView(actions);
        return box;
    }

    @Override
    public void onSelectionChanged() {
        final int hidden = overlay.hiddenControls().size();
        hiddenButton.setText(upper(getString(R.string.touch_hidden)) + "  " + hidden);
        if (tray.getVisibility() == View.VISIBLE) refreshTray();
        if (!overlay.hasSelection()) {
            toolbar.setVisibility(View.GONE);
            return;
        }
        toolbar.setVisibility(View.VISIBLE);
        toolbarTitle.setText(upper(overlay.selectedName()));
        final int percent = Math.round(overlay.selectedScale() * 100f);
        settingSize = true;
        if (sizeBar.getProgress() != percent - 50) sizeBar.setProgress(percent - 50);
        settingSize = false;
        sizeValue.setText(percent + "%");
        final int angle = Math.round(overlay.selectedAngle());
        angleValue.setText(angle == 0 ? "0°" : String.format(Locale.ROOT, "%+d°", angle).replace('-', '−'));
        toolbar.post(this::placeToolbar);
    }

    // Beside the selection, on the side with more room, so it never covers what is being edited.
    private void placeToolbar() {
        final float[] centre = overlay.selectedCentre();
        final float cx = overlay.getX() + centre[0], cy = overlay.getY() + centre[1];
        final int w = toolbar.getWidth(), h = toolbar.getHeight();
        final float gap = Math.min(root.getWidth(), root.getHeight()) * 0.30f;
        float x = cx > root.getWidth() / 2f ? cx - gap - w : cx + gap;
        x = Math.max(dp(12), Math.min(x, root.getWidth() - w - dp(12)));
        float y = Math.max(dp(72), Math.min(cy - h / 2f, root.getHeight() - h - dp(36)));
        toolbar.setX(x);
        toolbar.setY(y);
    }

    private void refreshTray() {
        tray.removeAllViews();
        TextView title = label(getString(R.string.touch_hidden_title), bold, 14, LABEL);
        title.setLetterSpacing(0.1f);
        tray.addView(title);
        List<String[]> hidden = overlay.hiddenControls();
        if (hidden.isEmpty()) {
            tray.addView(label(getString(R.string.touch_hidden_none), bold, 17, Color.WHITE));
            return;
        }
        LinearLayout line = null;
        for (int i = 0; i < hidden.size(); i++) {
            if (i % 2 == 0) {
                line = row();
                tray.addView(line);
            }
            final String key = hidden.get(i)[0];
            Button show = pill(hidden.get(i)[1] + "   + " + getString(R.string.touch_show), false);
            show.setContentDescription(getString(R.string.touch_show) + " " + hidden.get(i)[1]);
            show.setOnClickListener(v -> overlay.showControl(key));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i % 2 == 1) at.leftMargin = dp(8);
            line.addView(show, at);
        }
        if (hidden.size() % 2 == 1) line.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    // ---- Styling, matching the overlay: navy, cyan rims, condensed italics ----

    private LinearLayout panel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF132A55, 0xFF0A1736});
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(2), CYAN);
        box.setBackground(bg);
        box.setElevation(dp(10));
        return box;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        return row;
    }

    private TextView rowLabel(int text) {
        TextView label = label(getString(text), bold, 14, LABEL);
        label.setLetterSpacing(0.1f);
        label.setMinWidth(dp(64));
        return label;
    }

    private TextView label(String s, Typeface face, int sp, int colour) {
        TextView t = new TextView(this);
        t.setText(upper(s));
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        t.setShadowLayer(dp(2), 0, dp(1), 0xD9040A1A);
        return t;
    }

    private Button pill(String s, boolean hot) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(upper(s));
        b.setTypeface(bold);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setLetterSpacing(0.06f);
        b.setTextColor(hot ? 0xFF1A0D00 : Color.WHITE);
        GradientDrawable bg = hot
                ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFFFFB43A, 0xFFF07A0A})
                : new GradientDrawable();
        if (!hot) bg.setColor(NAVY);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(2), hot ? 0xFFFFE2AD : CYAN);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        return b;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2);
        at.leftMargin = dp(10);
        return at;
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.getDefault());
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
