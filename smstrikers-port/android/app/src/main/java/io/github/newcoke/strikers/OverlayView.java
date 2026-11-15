package io.github.newcoke.strikers;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Draws the touch gamepad, converts pointer input to virtual controller state, and, in the layout
 * editor, lets the player move, resize, rotate and hide each control.
 *
 * The look follows the game's own menus: navy panels with cyan rims, heavy condensed italics, slanted
 * shoulder buttons and an octagonal stick gate. Everything is drawn here, so it stays sharp at any
 * size or angle and no artwork ships with the app.
 */
public class OverlayView extends View {

    private static final int NAVY_TOP = 0xFF1D3D74, NAVY_BOTTOM = 0xFF0B1A3C;
    private static final int CYAN = 0xFF39C6FF, ORANGE = 0xFFFFB43A, OUTLINE = 0x8C030814;
    private static final int[] PASS = {0xFF5FDCFF, 0xFF1A8FDC, 0xFF0F63A8, 0xFFC9F3FF};
    private static final int[] SHOOT = {0xFFFFC35A, 0xFFF57C0A, 0xFFC4500A, 0xFFFFE2AD};
    private static final int[] PLAIN = {NAVY_TOP, NAVY_BOTTOM, NAVY_BOTTOM, CYAN};
    private static final float SKEW = 0.2493f;  // tan 14°, the slant of the game's menu bars

    private static final String[] PAD_KEYS = {"a", "b", "x", "y", "z", "start", "l", "r",
                "dpad_up", "dpad_down", "dpad_left", "dpad_right"};
    private static final String[] PAD_DEFAULTS = {"a", "b", "x", "y", "rightshoulder", "start", "lefttrigger",
                "righttrigger", "dpup", "dpdown", "dpleft", "dpright"};
    private static final String[] PAD_LABELS = {"A", "B", "X", "Y", "Z", "START", "L", "R", "↑", "↓", "←", "→"};
    // What each GameCube button does in a match, in PAD_KEYS order, from the game's own action
    // table (PadActions.cpp): attacking, then defending. 0 is no caption.
    private static final int[] ATTACK_CAPTIONS = {R.string.touch_pass, R.string.touch_shoot, R.string.touch_item,
            R.string.touch_deke, R.string.touch_next, 0, R.string.touch_aim, R.string.touch_turbo, 0, 0, 0, 0};
    private static final int[] DEFEND_CAPTIONS = {R.string.touch_switch, R.string.touch_tackle, R.string.touch_item,
            R.string.touch_hit, R.string.touch_next, 0, 0, R.string.touch_turbo, 0, 0, 0, 0};

    private enum Role { BUTTON, LEFT_TRIGGER, RIGHT_TRIGGER, STICK, C_STICK, MENU }
    private enum Style { FACE, SHOULDER, PILL, STICK, DPAD, MENU }

    /** The face buttons move, resize and rotate together, the way they sit under the thumb. */
    static final String FACE_GROUP = "face";

    private static final class Control {
        final String key;   // names the control in the saved layout
        final String name;  // shown in the editor
        final Style style;
        Role role = Role.BUTTON;
        int buttonMask;
        int arrow = -1;     // d-pad direction: 0 up, 1 down, 2 left, 3 right
        boolean inFaceGroup;
        float offX, offY;   // face buttons: offset from the group centre at scale 1
        float cx, cy;       // centre on screen
        float w, h;         // size at scale 1
        float scale = 1f, angle;
        boolean hidden, hiddenByDefault;
        String label = "";
        int caption;        // string resource, or 0
        int pointerId = -1; // active Android pointer, or -1
        float pressure;     // animated press, 0 to 1

        Control(String key, String name, Style style) {
            this.key = key;
            this.name = name;
            this.style = style;
        }

        boolean round() {
            return style == Style.FACE || style == Style.STICK || style == Style.DPAD;
        }

        float halfW() { return w * scale / 2f; }
        float halfH() { return h * scale / 2f; }

        // In the control's own frame, so a rotated shoulder button is hit where it is drawn.
        boolean contains(float x, float y) {
            final double a = Math.toRadians(-angle);
            final float dx = x - cx, dy = y - cy;
            final float lx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
            final float ly = (float) (dx * Math.sin(a) + dy * Math.cos(a));
            if (round()) {
                // Allow touches just outside the visible edge.
                final float r = halfW() * (style == Style.STICK ? 1.1f : 1.22f);
                return lx * lx + ly * ly <= r * r;
            }
            return Math.abs(lx) <= halfW() + halfH() * SKEW + halfH() * 0.2f && Math.abs(ly) <= halfH() * 1.25f;
        }
    }

    private final List<Control> controls = new ArrayList<>();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path clip = new Path();
    private final float dp;

    private float faceCx, faceCy, faceScale = 1f, faceAngle;

    private float leftStickDx, leftStickDy, cStickDx, cStickDy;
    private Control leftStick, cStick;

    private SharedPreferences positions;
    private boolean actionLabels, matchLabels, attacking;

    private static native boolean nativeMatchActive();
    private static native boolean nativeAttacking();
    private static native boolean nativeBindings(int[] output);
    // Mirrors the binding snapshot in touch.cpp. Button slots use TouchGamepad bit order.
    private static final int LEFT_TRIGGER_BINDING = 14;
    private static final int RIGHT_TRIGGER_BINDING = 15;
    private static final int LEFT_STICK_BINDING = 16;
    private static final int RIGHT_STICK_BINDING = 17;
    private static final int BINDING_COUNT = 18;
    private final int[] resolved = new int[BINDING_COUNT];
    private final int[] nextBindings = new int[BINDING_COUNT];
    private boolean hasResolvedBindings;
    private static final int[] PAD_BITS = {0x100, 0x200, 0x400, 0x800, 0x10, 0x1000,
            0x40, 0x20, 0x8, 0x4, 0x1, 0x2};

    private java.util.Map<String, String> bindings;
    private float opacity = 1f;
    private String layout = GamePreferences.TOUCH_STANDARD;
    private float size = 1f;
    private float deadzone = 0.08f;
    private boolean showDpad, showCStick;

    // Keep controls clear of system bars and cutouts.
    private int leftInset, rightInset, topInset, bottomInset;

    interface SettingsListener { void openSettings(); }
    private SettingsListener settingsListener;

    private boolean haptics;
    private final android.os.Vibrator vibrator;

    public OverlayView(Context ctx) {
        super(ctx);
        dp = Math.max(1f, getResources().getDisplayMetrics().density);
        vibrator = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        setFocusable(false);
        text.setTypeface(labelTypeface(ctx));
        text.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
    }

    static Typeface labelTypeface(Context ctx) {
        try {
            return ctx.getResources().getFont(R.font.barlow_condensed_extrabold_italic);
        } catch (RuntimeException e) {
            return Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
        }
    }

    void setSettingsListener(SettingsListener listener) {
        settingsListener = listener;
    }

    void applyPreferences(GamePreferences prefs) {
        actionLabels = prefs.getBoolean(GamePreferences.TOUCH_ACTION_LABELS);
        opacity = prefs.getInt(GamePreferences.TOUCH_OPACITY) / 100f;
        layout = prefs.getString(GamePreferences.TOUCH_LAYOUT);
        if (bindings == null) bindings = GameConfig.readSettings(getContext());
        // A new name for the new layout model, so positions saved for the old controls start fresh.
        positions = getContext().getSharedPreferences("touch_layout_v2_" + layout, Context.MODE_PRIVATE);
        size = prefs.getInt(GamePreferences.TOUCH_SIZE) / 100f;
        deadzone = prefs.getInt(GamePreferences.TOUCH_DEADZONE) / 100f;
        showDpad = prefs.getBoolean(GamePreferences.SHOW_DPAD);
        showCStick = prefs.getBoolean(GamePreferences.CSTICK);
        haptics = prefs.getBoolean(GamePreferences.TOUCH_HAPTICS);
        setAlpha(editing ? Math.max(opacity, 0.35f) : opacity);
        if (getWidth() > 0) layoutControls(getWidth(), getHeight());
        invalidate();
    }

    void updateMatchLabels() {
        boolean active = actionLabels && nativeMatchActive();
        boolean attack = active && nativeAttacking();
        boolean available = nativeBindings(nextBindings);
        boolean changed = available != hasResolvedBindings
                || (available && !Arrays.equals(resolved, nextBindings));
        hasResolvedBindings = available;
        if (available && changed) System.arraycopy(nextBindings, 0, resolved, 0, resolved.length);
        if (!changed && active == matchLabels && attack == attacking) return;
        matchLabels = active;
        attacking = attack;
        for (Control control : controls) updateLabel(control);
        invalidate();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets b = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            leftInset = b.left; rightInset = b.right; topInset = b.top; bottomInset = b.bottom;
        } else {
            leftInset = insets.getSystemWindowInsetLeft();
            rightInset = insets.getSystemWindowInsetRight();
            topInset = insets.getSystemWindowInsetTop();
            bottomInset = insets.getSystemWindowInsetBottom();
        }
        if (getWidth() > 0) {
            layoutControls(getWidth(), getHeight());
            invalidate();
        }
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        layoutControls(w, h);
    }

    // ---- Layout ----

    private Control add(String key, String name, Style style, Role role, int mask,
                        float cx, float cy, float w, float h) {
        Control c = new Control(key, name, style);
        c.role = role;
        c.buttonMask = mask;
        c.cx = cx; c.cy = cy; c.w = w; c.h = h;
        controls.add(c);
        return c;
    }

    private Control face(String key, int mask, float offX, float offY, float diameter) {
        Control c = add(key, key.toUpperCase(Locale.ROOT), Style.FACE, Role.BUTTON, mask, 0, 0, diameter, diameter);
        c.inFaceGroup = true;
        c.offX = offX; c.offY = offY;
        return c;
    }

    // Default places scale with the shorter side, so they stay within thumb reach on any screen.
    private void layoutControls(int w, int h) {
        releaseAll();
        endGesture();
        controls.clear();
        leftStick = cStick = null;
        if (w == 0 || h == 0) return;

        final float u = Math.min(w, h);
        final boolean compact = GamePreferences.TOUCH_COMPACT.equals(layout);
        final boolean tablet = GamePreferences.TOUCH_TABLET.equals(layout);
        final float s = size * (compact ? 0.85f : 1f);
        final float reach = tablet ? u * 0.06f : 0f;
        final float m = u * 0.045f;
        final float left = leftInset + m + reach, right = w - rightInset - m - reach;
        final float top = topInset + m, bottom = h - bottomInset - m;

        // LT and RT are GameCube L and R; RB is Z. LB, L3 and R3 do nothing in Strikers by default.
        final float sw = u * 0.30f * s, sh = u * 0.105f * s;
        add("lt", "L", Style.SHOULDER, Role.LEFT_TRIGGER, 0, left + sw / 2f + sh * SKEW, top + sh / 2f, sw, sh);
        add("rt", "R", Style.SHOULDER, Role.RIGHT_TRIGGER, 0, right - sw / 2f - sh * SKEW, top + sh / 2f, sw, sh);
        final float bw = u * 0.20f * s, bh = u * 0.085f * s;
        add("lb", "LB", Style.SHOULDER, Role.BUTTON, TouchGamepad.LB,
                left + bw / 2f + bh * SKEW, top + sh + bh, bw, bh).hiddenByDefault = true;
        add("l3", "L3", Style.SHOULDER, Role.BUTTON, TouchGamepad.L3,
                left + bw / 2f + bh * SKEW, top + sh + bh * 2.3f, bw, bh).hiddenByDefault = true;
        add("r3", "R3", Style.SHOULDER, Role.BUTTON, TouchGamepad.R3,
                right - bw / 2f - bh * SKEW, top + sh + bh * 2.3f, bw, bh).hiddenByDefault = true;

        // Face buttons: A and B, pass and shoot, the largest.
        faceCx = right - u * 0.33f * s;
        faceCy = bottom - u * 0.30f * s;
        faceScale = 1f;
        faceAngle = 0f;
        face("y", TouchGamepad.Y, 0f, -u * 0.15f * s, u * 0.135f * s);
        face("x", TouchGamepad.X, -u * 0.155f * s, 0f, u * 0.135f * s);
        face("b", TouchGamepad.B, u * 0.155f * s, -u * 0.01f * s, u * 0.165f * s);
        face("a", TouchGamepad.A, 0f, u * 0.145f * s, u * 0.18f * s);
        // Z cycles items, so it sits beside X, which uses them.
        add("z", "Z", Style.PILL, Role.BUTTON, TouchGamepad.Z,
                faceCx - u * 0.27f * s, faceCy - u * 0.145f * s, u * 0.15f * s, u * 0.07f * s);

        final float sr = u * 0.19f * s;
        leftStick = add("stick", getResources().getString(R.string.touch_stick), Style.STICK, Role.STICK, 0,
                left + sr * 1.05f, bottom - sr * 1.05f, sr * 2f, sr * 2f);
        final float cr = u * 0.14f * s;
        cStick = add("cstick", "C-STICK", Style.STICK, Role.C_STICK, 0, w * 0.62f, bottom - cr * 1.1f, cr * 2f, cr * 2f);
        cStick.hiddenByDefault = !showCStick;

        // The d-pad does nothing in a match, but menus can use it; each arrow is its own control.
        final float rd = u * 0.10f * s, dcx = left + sr * 1.05f, dcy = top + sh + u * 0.30f * s;
        final float gap = rd * 1.05f;
        final String[] arrowNames = {"↑", "↓", "←", "→"};
        final int[] arrowMasks = {TouchGamepad.UP, TouchGamepad.DOWN, TouchGamepad.LEFT, TouchGamepad.RIGHT};
        final float[][] arrowAt = {{0, -gap}, {0, gap}, {-gap, 0}, {gap, 0}};
        final String[] arrowKeys = {"up", "down", "left", "right"};
        for (int i = 0; i < 4; i++) {
            Control d = add("dpad_" + arrowKeys[i], "D-PAD " + arrowNames[i], Style.DPAD, Role.BUTTON, arrowMasks[i],
                    dcx + arrowAt[i][0], dcy + arrowAt[i][1], rd, rd);
            d.arrow = i;
            d.hiddenByDefault = !showDpad;
        }

        // Select (the online panel), settings and Start along the bottom edge.
        final float pw = u * 0.19f * s, ph = u * 0.07f * s, py = bottom - ph / 2f;
        add("select", "SELECT", Style.PILL, Role.BUTTON, TouchGamepad.BACK, w * 0.5f - u * 0.2f * s, py, pw, ph);
        add("menu", getResources().getString(R.string.settings), Style.MENU, Role.MENU, 0, w * 0.5f, py,
                u * 0.085f * s, ph);
        add("start", "START", Style.PILL, Role.BUTTON, TouchGamepad.START, w * 0.5f + u * 0.2f * s, py, pw, ph);

        applySaved();
        for (Control c : controls) updateLabel(c);
        if (editing) notifyEdit();
    }

    private void applySaved() {
        if (positions == null) {
            placeFaceGroup();
            return;
        }
        if (positions.contains(FACE_GROUP + "_x")) {
            faceCx = leftInset + positions.getFloat(FACE_GROUP + "_x", 0f) * usableWidth();
            faceCy = topInset + positions.getFloat(FACE_GROUP + "_y", 0f) * usableHeight();
        }
        faceScale = positions.getFloat(FACE_GROUP + "_s", 1f);
        faceAngle = positions.getFloat(FACE_GROUP + "_a", 0f);
        for (Control c : controls) {
            c.hidden = positions.getBoolean(c.key + "_h", c.hiddenByDefault);
            if (c.inFaceGroup) continue;
            c.scale = positions.getFloat(c.key + "_s", 1f);
            c.angle = positions.getFloat(c.key + "_a", 0f);
            if (positions.contains(c.key + "_x")) {
                c.cx = leftInset + positions.getFloat(c.key + "_x", 0f) * usableWidth();
                c.cy = topInset + positions.getFloat(c.key + "_y", 0f) * usableHeight();
            }
            clamp(c);
        }
        placeFaceGroup();
    }

    private void placeFaceGroup() {
        final double a = Math.toRadians(faceAngle);
        final float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        for (Control c : controls) {
            if (!c.inFaceGroup) continue;
            c.scale = faceScale;
            c.cx = faceCx + (c.offX * cos - c.offY * sin) * faceScale;
            c.cy = faceCy + (c.offX * sin + c.offY * cos) * faceScale;
        }
    }

    private int usableWidth() {
        return Math.max(1, getWidth() - leftInset - rightInset);
    }

    private int usableHeight() {
        return Math.max(1, getHeight() - topInset - bottomInset);
    }

    private void clamp(Control c) {
        final float hw = Math.max(c.halfW(), c.halfH()), hh = hw;
        c.cx = Math.max(leftInset + hw * 0.5f, Math.min(c.cx, getWidth() - rightInset - hw * 0.5f));
        c.cy = Math.max(topInset + hh * 0.5f, Math.min(c.cy, getHeight() - bottomInset - hh * 0.5f));
    }

    // ---- Labels ----

    private void updateLabel(Control control) {
        control.caption = 0;
        String source;
        switch (control.role) {
            case LEFT_TRIGGER: source = "lefttrigger"; break;
            case RIGHT_TRIGGER: source = "righttrigger"; break;
            case STICK:
            case C_STICK:
                control.label = "";
                if (hasResolvedBindings) {
                    int index = control.role == Role.STICK ? LEFT_STICK_BINDING : RIGHT_STICK_BINDING;
                    if (resolved[index] == 1) control.label = "C";
                    else if (control.role == Role.C_STICK) control.label = "LS";
                    return;
                }
                String swap = bindings.get("pad_swap_sticks");
                if (swap != null && !Arrays.asList("0", "false", "off", "no")
                        .contains(swap.toLowerCase(Locale.ROOT))) {
                    control.label = control.role == Role.STICK ? "C" : "LS";
                }
                return;
            case BUTTON:
                switch (control.buttonMask) {
                    case TouchGamepad.A: source = "a"; break;
                    case TouchGamepad.B: source = "b"; break;
                    case TouchGamepad.X: source = "x"; break;
                    case TouchGamepad.Y: source = "y"; break;
                    case TouchGamepad.Z: source = "rightshoulder"; break;
                    case TouchGamepad.LB: source = "leftshoulder"; break;
                    case TouchGamepad.START: control.label = "START"; return;
                    case TouchGamepad.BACK: control.label = "SELECT"; return;
                    case TouchGamepad.UP: source = "dpup"; break;
                    case TouchGamepad.DOWN: source = "dpdown"; break;
                    case TouchGamepad.LEFT: source = "dpleft"; break;
                    case TouchGamepad.RIGHT: source = "dpright"; break;
                    case TouchGamepad.L3: source = "leftstick"; break;
                    case TouchGamepad.R3: source = "rightstick"; break;
                    default: return;
                }
                break;
            default: return;
        }
        int resolvedIndex = control.role == Role.LEFT_TRIGGER ? LEFT_TRIGGER_BINDING
                : control.role == Role.RIGHT_TRIGGER ? RIGHT_TRIGGER_BINDING
                : Integer.numberOfTrailingZeros(control.buttonMask);
        StringBuilder assigned = new StringBuilder();
        int first = -1;
        for (int i = 0; i < PAD_KEYS.length; i++) {
            String mapped = bindings.getOrDefault("pad_" + PAD_KEYS[i], PAD_DEFAULTS[i]).toLowerCase(Locale.ROOT);
            boolean supported = Arrays.asList(PAD_DEFAULTS).contains(mapped)
                    || mapped.equals("leftshoulder") || mapped.equals("back")
                    || mapped.equals("leftstick") || mapped.equals("rightstick");
            boolean acceptsTrigger = PAD_KEYS[i].equals("l") || PAD_KEYS[i].equals("r");
            if (!supported || (!acceptsTrigger && mapped.endsWith("trigger"))) {
                mapped = PAD_DEFAULTS[i];
            }
            boolean assignedHere = hasResolvedBindings
                    ? (resolved[resolvedIndex] & PAD_BITS[i]) != 0 : source.equals(mapped);
            if (!assignedHere) continue;
            if (first < 0) first = i;
            if (assigned.length() != 0) assigned.append('/');
            assigned.append(PAD_LABELS[i]);
        }
        control.label = assigned.length() == 0 ? "–" : assigned.toString();
        // Captions follow the GameCube button, so a remapped Pass keeps its caption.
        if (matchLabels && first >= 0) control.caption = attacking ? ATTACK_CAPTIONS[first] : DEFEND_CAPTIONS[first];
    }

    // ---- Drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        for (Control c : controls) {
            if (c.hidden) continue;
            final float target = c.pointerId >= 0 ? 1f : 0f;
            c.pressure += (target - c.pressure) * 0.35f;
            if (Math.abs(target - c.pressure) > 0.003f) postInvalidateOnAnimation();
            else c.pressure = target;

            canvas.save();
            if (!c.inFaceGroup) canvas.rotate(c.angle, c.cx, c.cy);
            if (c.style != Style.STICK) {
                final float k = 1f - 0.08f * c.pressure;
                canvas.scale(k, k, c.cx, c.cy);
            }
            switch (c.style) {
                case FACE: drawFace(canvas, c); break;
                case DPAD: drawFace(canvas, c); break;
                case STICK: drawStick(canvas, c); break;
                default: drawSlanted(canvas, c); break;
            }
            canvas.restore();
        }
        if (editing) drawSelection(canvas);
    }

    private void drawFace(Canvas canvas, Control c) {
        final float r = c.halfW();
        final int[] look = c.style == Style.DPAD ? PLAIN
                : c.label.startsWith("A") ? PASS : c.label.startsWith("B") ? SHOOT : PLAIN;

        stroke.setStrokeWidth(dp * 2f);
        stroke.setColor(OUTLINE);
        canvas.drawCircle(c.cx, c.cy, r + dp, stroke);

        // Lit from the upper left, like the menu panels.
        shade(new LinearGradient(c.cx - r * 0.34f, c.cy - r * 0.94f, c.cx + r * 0.34f, c.cy + r * 0.94f,
                new int[]{look[0], look[1], look[2]}, new float[]{0f, look == PLAIN ? 0.7f : 0.62f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(c.cx, c.cy, r, fill);
        fill.setShader(null);

        // A slanted gloss across the top left, clipped to the button.
        canvas.save();
        clip.reset();
        clip.addCircle(c.cx, c.cy, r, Path.Direction.CW);
        canvas.clipPath(clip);
        path.reset();
        path.moveTo(c.cx - r * 1.1f, c.cy - r * 1.3f);
        path.lineTo(c.cx + r * 0.25f, c.cy - r * 1.3f);
        path.lineTo(c.cx - r * 0.25f, c.cy - r * 0.1f);
        path.lineTo(c.cx - r * 1.6f, c.cy - r * 0.1f);
        path.close();
        fill.setColor(Color.argb(33, 255, 255, 255));
        canvas.drawPath(path, fill);
        if (c.pressure > 0f) {
            fill.setColor(Color.argb(Math.round(46 * c.pressure), 255, 255, 255));
            canvas.drawCircle(c.cx, c.cy, r, fill);
        }
        canvas.restore();

        stroke.setStrokeWidth(dp * 3f);
        stroke.setColor(look[3]);
        canvas.drawCircle(c.cx, c.cy, r - dp * 1.5f, stroke);
        pressRing(canvas, c, () -> canvas.drawCircle(c.cx, c.cy, r + dp * 2.5f, stroke));

        if (c.style == Style.DPAD) {
            drawArrow(canvas, c, r);
            return;
        }
        final String caption = c.caption != 0 ? getResources().getString(c.caption).toUpperCase(Locale.getDefault()) : null;
        if (caption == null) {
            drawText(canvas, c.label, c.cx, c.cy, fitSize(c.label, r * 0.84f, r * 1.5f));
        } else {
            drawText(canvas, c.label, c.cx, c.cy - r * 0.16f, fitSize(c.label, r * 0.72f, r * 1.4f));
            drawText(canvas, caption, c.cx, c.cy + r * 0.42f, fitSize(caption, r * 0.27f, r * 1.5f), true);
        }
    }

    private void drawArrow(Canvas canvas, Control c, float r) {
        final float a = r * 0.42f;
        path.reset();
        path.moveTo(0, -a);
        path.lineTo(a * 0.95f, a * 0.62f);
        path.lineTo(-a * 0.95f, a * 0.62f);
        path.close();
        canvas.save();
        canvas.translate(c.cx, c.cy + (c.arrow == 0 ? -1 : c.arrow == 1 ? 1 : 0) * r * 0.04f);
        canvas.rotate(c.arrow == 1 ? 180 : c.arrow == 2 ? -90 : c.arrow == 3 ? 90 : 0);
        fill.setColor(Color.WHITE);
        fill.setShadowLayer(dp * 2f, 0, dp, 0xAA040A1A);
        canvas.drawPath(path, fill);
        fill.clearShadowLayer();
        canvas.restore();
    }

    private void slantedPath(Path out, float cx, float cy, float hw, float hh) {
        final float lean = hh * SKEW;
        out.reset();
        out.moveTo(cx - hw + lean, cy - hh);
        out.lineTo(cx + hw + lean, cy - hh);
        out.lineTo(cx + hw - lean, cy + hh);
        out.lineTo(cx - hw - lean, cy + hh);
        out.close();
    }

    private void drawSlanted(Canvas canvas, Control c) {
        final float hw = c.halfW(), hh = c.halfH();
        final CornerPathEffect corners = new CornerPathEffect(hh * 0.34f);
        slantedPath(path, c.cx, c.cy, hw, hh);

        stroke.setPathEffect(corners);
        stroke.setStrokeWidth(dp * 2f);
        stroke.setColor(OUTLINE);
        slantedPath(clip, c.cx, c.cy, hw + dp, hh + dp);
        canvas.drawPath(clip, stroke);

        fill.setPathEffect(corners);
        shade(new LinearGradient(0, c.cy - hh, 0, c.cy + hh, NAVY_TOP, NAVY_BOTTOM, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        if (c.pressure > 0f) {
            fill.setColor(Color.argb(Math.round(46 * c.pressure), 255, 255, 255));
            canvas.drawPath(path, fill);
        }
        fill.setPathEffect(null);

        // A thin highlight under the top edge.
        stroke.setPathEffect(null);
        stroke.setStrokeWidth(dp * 1.5f);
        stroke.setColor(Color.argb(40, 255, 255, 255));
        final float y = c.cy - hh + dp * 4f, lean = (hh - dp * 4f) * SKEW;
        canvas.drawLine(c.cx - hw + lean + hh * 0.3f, y, c.cx + hw + lean - hh * 0.3f, y, stroke);

        stroke.setPathEffect(corners);
        stroke.setStrokeWidth(dp * 3f);
        stroke.setColor(CYAN);
        canvas.drawPath(path, stroke);
        slantedPath(clip, c.cx, c.cy, hw + dp * 2.5f, hh + dp * 2.5f);
        pressRing(canvas, c, () -> canvas.drawPath(clip, stroke));
        stroke.setPathEffect(null);

        if (c.style == Style.MENU) {
            drawSettingsIcon(canvas, c.cx, c.cy, hh);
            return;
        }
        final String caption = c.caption != 0 ? getResources().getString(c.caption).toUpperCase(Locale.getDefault()) : null;
        if (caption == null) {
            final boolean word = c.label.length() > 2;
            drawText(canvas, c.label, c.cx, c.cy, fitSize(c.label, hh * (word ? 0.95f : 1.15f), hw * 1.6f), word);
        } else {
            // Letter and caption side by side, as one centred group.
            final float big = hh * 1.15f, small = hh * 0.56f;
            text.setTextSize(big);
            final float lw = text.measureText(c.label);
            text.setTextSize(small);
            final float cw = text.measureText(caption);
            final float gap = hh * 0.25f, total = lw + gap + cw;
            drawText(canvas, c.label, c.cx - total / 2f + lw / 2f, c.cy, big);
            drawText(canvas, caption, c.cx + total / 2f - cw / 2f, c.cy + big * 0.12f, small, true);
        }
    }

    private void drawSettingsIcon(Canvas canvas, float cx, float cy, float hh) {
        final float half = hh * 0.62f;
        final float[] knobs = {-0.3f, 0.45f, -0.55f};
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(dp * 1.8f);
        stroke.setColor(Color.WHITE);
        fill.setColor(Color.WHITE);
        for (int i = 0; i < 3; i++) {
            final float y = cy + (i - 1) * half * 0.6f;
            canvas.drawLine(cx - half, y, cx + half, y, stroke);
            canvas.drawCircle(cx + knobs[i] * half, y, dp * 2.6f, fill);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void octagon(Path out, float cx, float cy, float half) {
        out.reset();
        final float d = (float) (half / Math.cos(Math.PI / 8));
        for (int i = 0; i < 8; i++) {
            final double a = Math.PI / 8 + i * Math.PI / 4;
            final float x = cx + (float) Math.cos(a) * d, y = cy + (float) Math.sin(a) * d;
            if (i == 0) out.moveTo(x, y); else out.lineTo(x, y);
        }
        out.close();
    }

    private void drawStick(Canvas canvas, Control c) {
        final float half = c.halfW();
        final boolean isLeft = c == leftStick;
        final float dx = isLeft ? leftStickDx : cStickDx, dy = isLeft ? leftStickDy : cStickDy;
        final int rim = c.pressure > 0.5f ? ORANGE : CYAN;

        octagon(path, c.cx, c.cy, half);
        shade(new RadialGradient(c.cx, c.cy, half, 0x8C0D1E40, 0xD1061026, Shader.TileMode.CLAMP));
        canvas.drawPath(path, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(dp * 2f);
        stroke.setColor(OUTLINE);
        octagon(clip, c.cx, c.cy, half + dp * 1.5f);
        canvas.drawPath(clip, stroke);
        stroke.setStrokeWidth(dp * 3f);
        stroke.setColor(rim);
        canvas.drawPath(path, stroke);

        final float maxTravel = half * 0.5f;
        final float dist = (float) Math.hypot(dx, dy);
        final float k = dist > maxTravel ? maxTravel / dist : 1f;
        final float kx = c.cx + dx * k, ky = c.cy + dy * k, kr = half * 0.39f;
        fill.setShadowLayer(dp * 6f, 0, dp * 3f, 0x73000000);
        shade(new LinearGradient(kx - kr * 0.34f, ky - kr * 0.94f, kx + kr * 0.34f, ky + kr * 0.94f,
                0xFF25498A, 0xFF0C1C40, Shader.TileMode.CLAMP));
        canvas.drawCircle(kx, ky, kr, fill);
        fill.setShader(null);
        fill.clearShadowLayer();
        stroke.setColor(rim);
        if (c.pressure > 0.5f) stroke.setShadowLayer(dp * 8f, 0, 0, 0x99FFAA28);
        canvas.drawCircle(kx, ky, kr - dp * 1.5f, stroke);
        stroke.clearShadowLayer();
        // Grip grooves.
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(Math.max(dp * 2f, kr * 0.09f));
        stroke.setColor(0x5939C6FF);
        for (int i = -1; i <= 1; i++) {
            final float gy = ky + i * kr * 0.28f;
            canvas.drawLine(kx - kr * 0.55f, gy, kx + kr * 0.55f, gy, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
        if (!c.label.isEmpty()) drawText(canvas, c.label, kx, ky, kr * 0.7f);
    }

    // A shader draws at the paint colour's opacity, so set it back to opaque first: the gloss and
    // press overlays leave it low, which faded every gradient drawn after them.
    private void shade(Shader shader) {
        fill.setColor(Color.WHITE);
        fill.setShader(shader);
    }

    // The orange ring and glow of a pressed button.
    private void pressRing(Canvas canvas, Control c, Runnable draw) {
        if (c.pressure <= 0f) return;
        stroke.setStrokeWidth(dp * 3f);
        stroke.setColor(Color.argb(Math.round(255 * c.pressure), 0xFF, 0xB4, 0x3A));
        stroke.setShadowLayer(dp * 10f, 0, 0, Color.argb(Math.round(165 * c.pressure), 0xFF, 0xAA, 0x28));
        draw.run();
        stroke.clearShadowLayer();
    }

    private float fitSize(String s, float size, float maxWidth) {
        text.setTextSize(size);
        final float w = text.measureText(s);
        return w > maxWidth ? size * maxWidth / w : size;
    }

    private void drawText(Canvas canvas, String s, float cx, float cy, float size) {
        drawText(canvas, s, cx, cy, size, false);
    }

    private void drawText(Canvas canvas, String s, float cx, float cy, float size, boolean spaced) {
        if (s == null || s.isEmpty()) return;
        text.setTextSize(size);
        text.setLetterSpacing(spaced ? 0.08f : 0.02f);
        text.setColor(Color.WHITE);
        // A hard dark edge below the letters, like the game's menu type, so they read over the pitch.
        text.setShadowLayer(Math.max(dp, size * 0.08f), 0, Math.max(dp, size * 0.06f), 0xD9040A1A);
        final Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
        text.clearShadowLayer();
    }

    // ---- Play ----

    // A pointer stays with the control it pressed until release or cancellation.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) return editTouch(e);
        final int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int i = e.getActionIndex();
                press(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                final int index = e.getActionIndex();
                release(e.getPointerId(index), e.getX(index), e.getY(index));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
        }
        sendState();
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private Control hit(float x, float y) {
        for (int i = controls.size() - 1; i >= 0; --i) {
            Control c = controls.get(i);
            if (!c.hidden && c.contains(x, y)) return c;
        }
        return null;
    }

    private void press(int pointerId, float x, float y) {
        Control c = hit(x, y);
        if (c == null || c.pointerId >= 0) return;
        c.pointerId = pointerId;
        if (c.style == Style.STICK) move(pointerId, x, y);
        else tick();
    }

    // A short tick under the thumb, so a touch button feels pressed. Through the vibrator rather
    // than performHapticFeedback, which many phones turn off system-wide for the keyboard.
    private void tick() {
        if (!haptics || vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(12, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void move(int pointerId, float x, float y) {
        for (Control c : controls) {
            if (c.pointerId != pointerId || c.style != Style.STICK) continue;
            // In the stick's own frame, so a rotated stick still pushes the way the thumb moves.
            final double a = Math.toRadians(-c.angle);
            final float dx = x - c.cx, dy = y - c.cy;
            final float lx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
            final float ly = (float) (dx * Math.sin(a) + dy * Math.cos(a));
            if (c == leftStick) { leftStickDx = lx; leftStickDy = ly; }
            else { cStickDx = lx; cStickDy = ly; }
        }
    }

    private void release(int pointerId, float x, float y) {
        for (Control control : controls) {
            if (control.pointerId != pointerId) continue;
            control.pointerId = -1;
            if (control == leftStick) leftStickDx = leftStickDy = 0;
            if (control == cStick) cStickDx = cStickDy = 0;
            if (control.role == Role.MENU && control.contains(x, y) && settingsListener != null) {
                settingsListener.openSettings();
            }
            return;
        }
    }

    void releaseAll() {
        for (Control control : controls) control.pointerId = -1;
        leftStickDx = leftStickDy = cStickDx = cStickDy = 0;
        sendState();
        invalidate();
    }

    private void sendState() {
        if (editing) return;
        int buttons = 0;
        int leftTrigger = 0, rightTrigger = 0;
        for (Control c : controls) {
            if (c.pointerId < 0) continue;
            switch (c.role) {
                case BUTTON:        buttons |= c.buttonMask; break;
                case LEFT_TRIGGER:  leftTrigger = 255; break;
                case RIGHT_TRIGGER: rightTrigger = 255; break;
                default: break;
            }
        }
        final int[] m = stickAxes(leftStick, leftStickDx, leftStickDy);
        final int[] c = stickAxes(cStick != null && !cStick.hidden ? cStick : null, cStickDx, cStickDy);
        TouchGamepad.sendState(buttons, leftTrigger, rightTrigger, m[0], m[1], c[0], c[1]);
    }

    // SDL stick Y increases downward. Apply the deadzone radially, then rescale the remaining travel.
    private int[] stickAxes(Control stick, float dx, float dy) {
        if (stick == null || stick.halfW() <= 0) return new int[]{0, 0};
        final float travel = stick.halfW() * 0.55f;
        float x = dx / travel, y = dy / travel;
        float magnitude = (float) Math.hypot(x, y);
        if (magnitude <= deadzone) return new int[]{0, 0};
        if (magnitude > 1f) { x /= magnitude; y /= magnitude; magnitude = 1f; }
        final float scaledMagnitude = (magnitude - deadzone) / (1f - deadzone);
        final float k = scaledMagnitude / magnitude;
        return new int[]{
            Math.round(Math.max(-1f, Math.min(1f, x * k)) * 32767f),
            Math.round(Math.max(-1f, Math.min(1f, y * k)) * 32767f)
        };
    }

    // ---- Editing ----

    /** What the layout editor shows about the selection and the hidden controls. */
    interface EditListener {
        void onSelectionChanged();
    }

    private boolean editing;
    private EditListener editListener;
    private String selected;  // FACE_GROUP, a control key, or null
    private boolean dragging;
    private float dragX, dragY;
    private float startSpan, startTwist, startScale, startAngle;
    private boolean twisting;

    void enableEditing() {
        editing = true;
        setAlpha(Math.max(opacity, 0.35f));
    }

    void setOpacity(float value) {
        opacity = value;
        setAlpha(editing ? Math.max(opacity, 0.35f) : opacity);
    }

    void setEditListener(EditListener listener) {
        editListener = listener;
    }

    boolean hasSelection() {
        return selected != null;
    }

    String selectedName() {
        if (selected == null) return "";
        if (FACE_GROUP.equals(selected)) return getResources().getString(R.string.touch_face_buttons);
        Control c = find(selected);
        return c == null ? "" : c.name;
    }

    float selectedScale() {
        if (FACE_GROUP.equals(selected)) return faceScale;
        Control c = find(selected);
        return c == null ? 1f : c.scale;
    }

    float selectedAngle() {
        if (FACE_GROUP.equals(selected)) return faceAngle;
        Control c = find(selected);
        return c == null ? 0f : c.angle;
    }

    /** The selection's centre on screen, for placing the editor's toolbar beside it. */
    float[] selectedCentre() {
        if (FACE_GROUP.equals(selected)) return new float[]{faceCx, faceCy};
        Control c = find(selected);
        return c == null ? new float[]{getWidth() / 2f, getHeight() / 2f} : new float[]{c.cx, c.cy};
    }

    void setSelectedScale(float scale) {
        scale = Math.max(0.5f, Math.min(2f, scale));
        if (FACE_GROUP.equals(selected)) {
            faceScale = scale;
            placeFaceGroup();
        } else {
            Control c = find(selected);
            if (c == null) return;
            c.scale = scale;
            clamp(c);
        }
        saveSelected();
        invalidate();
    }

    void rotateSelected(float degrees) {
        setSelectedAngle(selectedAngle() + degrees);
        saveSelected();
    }

    private void setSelectedAngle(float angle) {
        angle = ((angle % 360f) + 540f) % 360f - 180f;
        if (Math.abs(angle) < 4f) angle = 0f;  // snap back to level
        if (FACE_GROUP.equals(selected)) {
            faceAngle = angle;
            placeFaceGroup();
        } else {
            Control c = find(selected);
            if (c != null) c.angle = angle;
        }
        invalidate();
    }

    void hideSelected() {
        if (selected == null) return;
        SharedPreferences.Editor edit = positions.edit();
        for (Control c : controls) {
            if (FACE_GROUP.equals(selected) ? c.inFaceGroup : c.key.equals(selected)) {
                c.hidden = true;
                c.pointerId = -1;
                edit.putBoolean(c.key + "_h", true);
            }
        }
        edit.apply();
        select(null);
    }

    void resetSelected() {
        if (selected == null) return;
        SharedPreferences.Editor edit = positions.edit();
        final String[] keys = FACE_GROUP.equals(selected) ? new String[]{FACE_GROUP} : new String[]{selected};
        for (String key : keys) {
            edit.remove(key + "_x").remove(key + "_y").remove(key + "_s").remove(key + "_a");
        }
        edit.apply();
        final String keep = selected;
        layoutControls(getWidth(), getHeight());
        selected = keep;
        notifyEdit();
        invalidate();
    }

    void resetAll() {
        positions.edit().clear().apply();
        selected = null;
        layoutControls(getWidth(), getHeight());
        notifyEdit();
        invalidate();
    }

    /** Hidden controls as {key, name} pairs, for the editor's tray. */
    List<String[]> hiddenControls() {
        List<String[]> out = new ArrayList<>();
        boolean faceListed = false;
        for (Control c : controls) {
            if (!c.hidden) continue;
            if (c.inFaceGroup) {
                if (faceListed) continue;
                faceListed = true;
                out.add(new String[]{FACE_GROUP, getResources().getString(R.string.touch_face_buttons)});
            } else {
                out.add(new String[]{c.key, c.name});
            }
        }
        return out;
    }

    void showControl(String key) {
        SharedPreferences.Editor edit = positions.edit();
        for (Control c : controls) {
            if (FACE_GROUP.equals(key) ? c.inFaceGroup : c.key.equals(key)) {
                c.hidden = false;
                edit.putBoolean(c.key + "_h", false);
            }
        }
        edit.apply();
        select(key);
    }

    private Control find(String key) {
        if (key == null) return null;
        for (Control c : controls) if (c.key.equals(key)) return c;
        return null;
    }

    private void select(String key) {
        selected = key;
        notifyEdit();
        invalidate();
    }

    private void notifyEdit() {
        if (editListener != null) editListener.onSelectionChanged();
    }

    private void saveSelected() {
        if (selected == null || positions == null) return;
        SharedPreferences.Editor edit = positions.edit();
        if (FACE_GROUP.equals(selected)) {
            edit.putFloat(FACE_GROUP + "_x", (faceCx - leftInset) / usableWidth())
                .putFloat(FACE_GROUP + "_y", (faceCy - topInset) / usableHeight())
                .putFloat(FACE_GROUP + "_s", faceScale)
                .putFloat(FACE_GROUP + "_a", faceAngle);
        } else {
            Control c = find(selected);
            if (c == null) return;
            edit.putFloat(c.key + "_x", (c.cx - leftInset) / usableWidth())
                .putFloat(c.key + "_y", (c.cy - topInset) / usableHeight())
                .putFloat(c.key + "_s", c.scale)
                .putFloat(c.key + "_a", c.angle);
        }
        edit.apply();
        notifyEdit();
    }

    private void endGesture() {
        dragging = false;
        twisting = false;
    }

    private float span(MotionEvent e) {
        return (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
    }

    private float twist(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }

    // One finger drags the control under it; a second finger anywhere pinches and twists the selection.
    private boolean editTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float x = e.getX(), y = e.getY();
                Control c = hit(x, y);
                if (c == null) {
                    select(null);
                    break;
                }
                select(c.inFaceGroup ? FACE_GROUP : c.key);
                final float[] centre = selectedCentre();
                dragX = x - centre[0];
                dragY = y - centre[1];
                dragging = true;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (selected != null && e.getPointerCount() == 2) {
                    dragging = false;
                    twisting = true;
                    startSpan = Math.max(1f, span(e));
                    startTwist = twist(e);
                    startScale = selectedScale();
                    startAngle = selectedAngle();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (twisting && e.getPointerCount() >= 2) {
                    final float scale = Math.max(0.5f, Math.min(2f, startScale * span(e) / startSpan));
                    if (FACE_GROUP.equals(selected)) {
                        faceScale = scale;
                    } else {
                        Control c = find(selected);
                        if (c != null) c.scale = scale;
                    }
                    setSelectedAngle(startAngle + twist(e) - startTwist);
                    notifyEdit();
                } else if (dragging) {
                    final float x = e.getX() - dragX, y = e.getY() - dragY;
                    if (FACE_GROUP.equals(selected)) {
                        faceCx = Math.max(leftInset, Math.min(x, getWidth() - rightInset));
                        faceCy = Math.max(topInset, Math.min(y, getHeight() - bottomInset));
                        placeFaceGroup();
                    } else {
                        Control c = find(selected);
                        if (c != null) {
                            c.cx = x;
                            c.cy = y;
                            clamp(c);
                        }
                    }
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (twisting) {
                    twisting = false;
                    saveSelected();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging || twisting) {
                    endGesture();
                    saveSelected();
                    performClick();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                endGesture();
                break;
        }
        invalidate();
        return true;
    }

    // A dashed box with corner handles around the selection, and a rotate handle above it.
    private void drawSelection(Canvas canvas) {
        if (selected == null) return;
        float cx, cy, hw, hh, angle;
        if (FACE_GROUP.equals(selected)) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (Control c : controls) {
                if (!c.inFaceGroup || c.hidden) continue;
                final float r = c.halfW();
                minX = Math.min(minX, c.offX * faceScale - r); maxX = Math.max(maxX, c.offX * faceScale + r);
                minY = Math.min(minY, c.offY * faceScale - r); maxY = Math.max(maxY, c.offY * faceScale + r);
            }
            if (minX > maxX) return;
            cx = faceCx; cy = faceCy; angle = faceAngle;
            hw = Math.max(Math.abs(minX), Math.abs(maxX)); hh = Math.max(Math.abs(minY), Math.abs(maxY));
        } else {
            Control c = find(selected);
            if (c == null || c.hidden) return;
            cx = c.cx; cy = c.cy; angle = c.angle;
            hw = c.halfW() + (c.round() ? 0 : c.halfH() * SKEW); hh = c.halfH();
        }
        final float pad = dp * 10f;
        canvas.save();
        canvas.rotate(angle, cx, cy);
        final RectF box = new RectF(cx - hw - pad, cy - hh - pad, cx + hw + pad, cy + hh + pad);
        stroke.setStrokeWidth(dp * 2f);
        stroke.setColor(CYAN);
        stroke.setPathEffect(new DashPathEffect(new float[]{dp * 7f, dp * 5f}, 0));
        canvas.drawRoundRect(box, dp * 8f, dp * 8f, stroke);
        stroke.setPathEffect(null);
        final float hs = dp * 6f;
        fill.setColor(Color.WHITE);
        stroke.setStrokeWidth(dp * 2.5f);
        for (float[] p : new float[][]{{box.left, box.top}, {box.right, box.top}, {box.left, box.bottom}, {box.right, box.bottom}}) {
            canvas.drawRect(p[0] - hs, p[1] - hs, p[0] + hs, p[1] + hs, fill);
            canvas.drawRect(p[0] - hs, p[1] - hs, p[0] + hs, p[1] + hs, stroke);
        }
        canvas.drawLine(cx, box.top, cx, box.top - dp * 22f, stroke);
        fill.setColor(ORANGE);
        canvas.drawCircle(cx, box.top - dp * 30f, dp * 9f, fill);
        stroke.setColor(Color.WHITE);
        canvas.drawCircle(cx, box.top - dp * 30f, dp * 9f, stroke);
        canvas.restore();
    }
}
