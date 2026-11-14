package io.github.newcoke.strikers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;

/** Draws the touch gamepad and converts pointer input to virtual controller state. */
public class OverlayView extends View {

    private static final int CONTROL_ACCENT = 0xFFF2E9D4;
    private static final int SETTINGS_ACCENT = 0xFFFFC552;

    // The metal style: chrome rims, dark panels, an electric accent for presses and action words.
    private static final int METAL_PANEL = 0xFF111821;
    private static final int METAL_ACCENT = 0xFFC6FF3D;
    private static final int METAL_PRESSED_TEXT = 0xFF0B1206;
    private static final int METAL_LABEL_IDLE = 0xFFB9C2CD;
    // One unit-square gradient each, placed on a shape with a local matrix, so drawing allocates
    // nothing.
    private static final Shader CHROME = new LinearGradient(0f, 0f, 1f, 1f,
            new int[] {0xFFF5F7FA, 0xFF8D97A3, 0xFFE2E6EB}, new float[] {0f, 0.55f, 1f},
            Shader.TileMode.CLAMP);
    private static final Shader GOLD = new LinearGradient(0f, 0f, 1f, 1f,
            new int[] {0xFFFFE08A, 0xFFC98A12, 0xFFFFD36B}, new float[] {0f, 0.55f, 1f},
            Shader.TileMode.CLAMP);

    private static final String[] PAD_KEYS = {"a", "b", "x", "y", "z", "start", "l", "r",
                "dpad_up", "dpad_down", "dpad_left", "dpad_right"};
    private static final String[] PAD_DEFAULTS = {"a", "b", "x", "y", "rightshoulder", "start", "lefttrigger",
                "righttrigger", "dpup", "dpdown", "dpleft", "dpright"};
    private static final String[] PAD_LABELS = {"A", "B", "X", "Y", "Z", "START", "L", "R", "↑", "↓", "←", "→"};
    private enum Role { BUTTON, LEFT_TRIGGER, RIGHT_TRIGGER, STICK, C_STICK, MENU }

    private enum Shape { CIRCLE, RECTANGLE, STICK }

    private static final class Control {
        String label;
        final Shape shape;
        Role role = Role.BUTTON;
        int buttonMask;
        float cx, cy, radius;
        final RectF bounds = new RectF();
        int pointerId = -1;  // Active Android pointer ID, or -1.
        float pressure;  // Animated press, from 0 to 1.
        float hitScale = 1.25f;  // Touch radius beyond the drawn one, for round controls.

        Control(String label, Shape shape) {
            this.label = label;
            this.shape = shape;
        }

        boolean contains(float x, float y) {
            if (shape == Shape.CIRCLE || shape == Shape.STICK) {
                final float dx = x - cx, dy = y - cy;
                // Allow touches just outside the visible circle.
                final float hitRadius = radius * hitScale;
                return dx * dx + dy * dy <= hitRadius * hitRadius;
            }
            return bounds.contains(x, y);
        }
    }

    private final List<Control> controls = new ArrayList<>();
    private final Paint controlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path innerPath = new Path();
    private final Paint metalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private boolean metal;
    private Typeface metalFont;
    private final String shootWord, passWord, lobWord;
    private final Typeface classicFont = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private float leftStickDx, leftStickDy, cStickDx, cStickDy;
    private Control leftStick, cStick;

    private android.content.SharedPreferences positions;
    private Control dragging;
    private String selectedControl;
    private boolean actionLabels, matchLabels;

    private static native boolean nativeMatchActive();
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

    void updateMatchLabels() {
        boolean active = actionLabels && nativeMatchActive();
        boolean available = nativeBindings(nextBindings);
        boolean changed = available != hasResolvedBindings
                || (available && !java.util.Arrays.equals(resolved, nextBindings));
        hasResolvedBindings = available;
        if (available && changed) System.arraycopy(nextBindings, 0, resolved, 0, resolved.length);
        if (!changed && active == matchLabels) return;
        matchLabels = active;
        for (Control control : controls) updateLabel(control);
        invalidate();
    }

    void resizeSelected(float delta) {
        if (selectedControl == null) return;
        String key = selectedControl + "_size";
        float scale = positions.getFloat(key, 1f) + delta;
        positions.edit().putFloat(key, Math.max(0.65f, Math.min(1.45f, scale))).apply();
        layoutControls(getWidth(), getHeight());
        invalidate();
    }
    private java.util.Map<String, String> bindings;
    private boolean editing;
    private float dragX, dragY;

    void enableEditing() {
        editing = true;
        opacity = Math.max(opacity, 0.75f);
        showDpad = showCStick = true;
    }

    void resetPositions() {
        selectedControl = null;
        positions.edit().clear().apply();
        layoutControls(getWidth(), getHeight());
        invalidate();
    }

    private float opacity = 1f;
    private String layout = GamePreferences.TOUCH_STANDARD;
    private float size = 1f;
    private float deadzone = 0.08f;
    private boolean showDpad;
    private boolean showCStick;

    // Keep controls clear of system bars and cutouts.
    private int leftInset, rightInset, topInset, bottomInset;

    interface SettingsListener { void openSettings(); }
    private SettingsListener settingsListener;

    private boolean haptics;
    private final android.os.Vibrator vibrator;

    public OverlayView(Context ctx) {
        super(ctx);
        vibrator = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        setFocusable(false);
        labelPaint.setTypeface(classicFont);
        shootWord = ctx.getString(R.string.touch_shoot);
        passWord = ctx.getString(R.string.touch_pass);
        lobWord = ctx.getString(R.string.touch_lob);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    void setSettingsListener(SettingsListener listener) {
        settingsListener = listener;
    }

    void applyPreferences(GamePreferences prefs) {
        actionLabels = prefs.getBoolean(GamePreferences.TOUCH_ACTION_LABELS);
        opacity = prefs.getInt(GamePreferences.TOUCH_OPACITY) / 100f;
        layout = prefs.getString(GamePreferences.TOUCH_LAYOUT);
        if (bindings == null) bindings = GameConfig.readSettings(getContext());
        positions = getContext().getSharedPreferences("touch_positions_" + layout, Context.MODE_PRIVATE);
        size = prefs.getInt(GamePreferences.TOUCH_SIZE) / 100f;
        deadzone = prefs.getInt(GamePreferences.TOUCH_DEADZONE) / 100f;
        showDpad = prefs.getBoolean(GamePreferences.SHOW_DPAD);
        showCStick = prefs.getBoolean(GamePreferences.CSTICK);
        haptics = prefs.getBoolean(GamePreferences.TOUCH_HAPTICS);
        metal = !GamePreferences.TOUCH_STYLE_CLASSIC.equals(prefs.getString(GamePreferences.TOUCH_STYLE));
        if (metal && metalFont == null) {
            try {
                metalFont = Typeface.createFromAsset(getContext().getAssets(),
                        "fonts/RussoOne-Regular.ttf");
            } catch (RuntimeException e) {
                metalFont = Typeface.create("sans-serif", Typeface.BOLD);
            }
        }
        labelPaint.setTypeface(metal ? metalFont : classicFont);
        if (getWidth() > 0) {
            layoutControls(getWidth(), getHeight());
        }
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

    // Scale positions against the landscape bounds to keep controls within thumb reach.
    private void layoutControls(int w, int h) {
        releaseAll();
        dragging = null;
        controls.clear();
        leftStick = cStick = null;
        if (w == 0 || h == 0) return;

        final float u = Math.min(w, h);
        final boolean compact = GamePreferences.TOUCH_COMPACT.equals(layout);
        final boolean tablet = GamePreferences.TOUCH_TABLET.equals(layout);
        final float scale = size * (compact ? 0.85f : 1f);
        final float reachInset = tablet ? u * 0.06f : 0f;
        final float r = u * 0.062f * scale;
        final float margin = u * 0.055f;

        final float left = margin + leftInset + reachInset;
        final float right = w - margin - rightInset - reachInset;
        final float top = margin + topInset;
        final float bottom = h - margin - bottomInset;

        // LT and RT map to GameCube L and R. Either bumper maps to Z.
        final float shoulderWidth = u * 0.155f * scale, shoulderHeight = u * 0.085f * scale;

        float y = top;
        final Role[] leftRoles = {Role.LEFT_TRIGGER, Role.BUTTON, Role.BUTTON};
        final int[] leftMasks = {0, TouchGamepad.LB, TouchGamepad.L3};
        final String[] leftLabels = {"LT", "LB", "L3"};
        for (int i = 0; i < 3; i++) {
            Control c = new Control(leftLabels[i], Shape.RECTANGLE);
            c.role = leftRoles[i];
            c.buttonMask = leftMasks[i];
            c.bounds.set(left, y, left + shoulderWidth, y + shoulderHeight);
            controls.add(c);
            y += shoulderHeight * 1.18f;
        }

        y = top;
        final Role[] rightRoles = {Role.RIGHT_TRIGGER, Role.BUTTON, Role.BUTTON};
        final int[] rightMasks = {0, TouchGamepad.Z, TouchGamepad.R3};
        final String[] rightLabels = {"RT", "RB", "R3"};
        for (int i = 0; i < 3; i++) {
            Control c = new Control(rightLabels[i], Shape.RECTANGLE);
            c.role = rightRoles[i];
            c.buttonMask = rightMasks[i];
            c.bounds.set(right - shoulderWidth, y, right, y + shoulderHeight);
            controls.add(c);
            y += shoulderHeight * 1.18f;
        }

        final float fx = right - r * 2.4f;
        final float fy = bottom - r * 2.4f;
        final float d = r * 1.55f;
        if (metal) {
            // An arc around a larger B (Shoot by default) and A (Pass), as drawn hexagons.
            controls.add(face("Y", fx - 0.52f * r, fy - 2.67f * r, 0.95f * r, TouchGamepad.Y));
            controls.add(face("X", fx - 2.75f * r, fy - 1.18f * r, 0.95f * r, TouchGamepad.X));
            controls.add(face("B", fx + 0.72f * r, fy + 0.23f * r, 1.65f * r, TouchGamepad.B));
            controls.add(face("A", fx - 2.25f * r, fy + 1.30f * r, 1.28f * r, TouchGamepad.A));
            // The hexagons are bigger, so their touch margin is smaller, to keep neighbours apart.
            for (int i = controls.size() - 4; i < controls.size(); i++) controls.get(i).hitScale = 1.08f;
        } else {
            controls.add(face("Y", fx, fy - d, r, TouchGamepad.Y));
            controls.add(face("X", fx - d, fy, r, TouchGamepad.X));
            controls.add(face("B", fx + d, fy, r, TouchGamepad.B));
            controls.add(face("A", fx, fy + d, r, TouchGamepad.A));
        }

        final float rm = u * 0.115f * scale;
        leftStick = new Control("", Shape.STICK);
        leftStick.role = Role.STICK;
        leftStick.cx = left + rm * 1.15f;
        leftStick.cy = bottom - rm * 1.25f;
        leftStick.radius = rm;
        controls.add(leftStick);

        if (showCStick) {
            cStick = new Control("", Shape.STICK);
            cStick.role = Role.C_STICK;
            cStick.cx = w * 0.665f;
            cStick.cy = bottom - rm * 1.55f;
            cStick.radius = rm * 0.86f;
            controls.add(cStick);
        }

        if (showDpad) {
            final float rd = u * 0.045f * scale;
            final float dcx = left + rm * 1.15f;
            final float dcy = h * 0.50f;
            final float spacing = rd * 1.95f;
            controls.add(face("▲", dcx, dcy - spacing, rd, TouchGamepad.UP));
            controls.add(face("▼", dcx, dcy + spacing, rd, TouchGamepad.DOWN));
            controls.add(face("◀", dcx - spacing, dcy, rd, TouchGamepad.LEFT));
            controls.add(face("▶", dcx + spacing, dcy, rd, TouchGamepad.RIGHT));
        }

        // The metal style's centre buttons are a size larger: the classic ones are hard to hit.
        final float centerScale = metal ? 1.25f : 1f;
        final float centerButtonWidth = u * 0.115f * scale * centerScale;
        final float centerButtonHeight = u * 0.052f * scale * centerScale;
        final float settingsRadius = u * 0.036f * scale;
        final float centerGap = u * 0.028f;

        Control back = new Control("BACK", Shape.RECTANGLE);
        back.buttonMask = TouchGamepad.BACK;
        back.bounds.set(w * 0.5f - settingsRadius - centerGap - centerButtonWidth, bottom - centerButtonHeight,
                       w * 0.5f - settingsRadius - centerGap, bottom);
        controls.add(back);

        Control start = new Control("START", Shape.RECTANGLE);
        start.buttonMask = TouchGamepad.START;
        start.bounds.set(w * 0.5f + settingsRadius + centerGap, bottom - centerButtonHeight,
                         w * 0.5f + settingsRadius + centerGap + centerButtonWidth, bottom);
        controls.add(start);

        Control menu = new Control("", Shape.CIRCLE);
        menu.role = Role.MENU;
        menu.cx = w * 0.5f;
        menu.cy = bottom - centerButtonHeight / 2f;
        menu.radius = settingsRadius;
        controls.add(menu);
        for (Control control : controls) {
            updateLabel(control);
            String key = positionKey(control);
            if (positions == null) continue;
            float scaleFactor = positions.getFloat(key + "_size", 1f);
            boolean rectangle = control.shape == Shape.RECTANGLE;
            float x = rectangle ? control.bounds.centerX() : control.cx;
            float centerY = rectangle ? control.bounds.centerY() : control.cy;
            if (rectangle) {
                control.bounds.inset(control.bounds.width() * (1f - scaleFactor) / 2f,
                        control.bounds.height() * (1f - scaleFactor) / 2f);
            } else {
                control.radius *= scaleFactor;
            }
            if (positions.contains(key + "_x")) {
                moveControl(control, leftInset + positions.getFloat(key + "_x", 0f) * usableWidth(),
                        topInset + positions.getFloat(key + "_y", 0f) * usableHeight());
            } else {
                moveControl(control, x, centerY);
            }
        }
    }

    private void updateLabel(Control control) {
        String source;
        switch (control.role) {
            case LEFT_TRIGGER: source = "lefttrigger"; break;
            case RIGHT_TRIGGER: source = "righttrigger"; break;
            case STICK:
            case C_STICK:
                if (hasResolvedBindings) {
                    int index = control.role == Role.STICK ? LEFT_STICK_BINDING : RIGHT_STICK_BINDING;
                    control.label = resolved[index] == 1 ? "C" : "LS";
                    return;
                }
                String swap = bindings.get("pad_swap_sticks");
                if (swap != null && !java.util.Arrays.asList("0", "false", "off", "no")
                        .contains(swap.toLowerCase(java.util.Locale.ROOT))) {
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
                    case TouchGamepad.START: source = "start"; break;
                    case TouchGamepad.UP: source = "dpup"; break;
                    case TouchGamepad.DOWN: source = "dpdown"; break;
                    case TouchGamepad.LEFT: source = "dpleft"; break;
                    case TouchGamepad.RIGHT: source = "dpright"; break;
                    case TouchGamepad.BACK: source = "back"; break;
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
        for (int i = 0; i < PAD_KEYS.length; i++) {
            String mapped = bindings.getOrDefault("pad_" + PAD_KEYS[i], PAD_DEFAULTS[i]).toLowerCase(java.util.Locale.ROOT);
            boolean supported = java.util.Arrays.asList(PAD_DEFAULTS).contains(mapped)
                    || mapped.equals("leftshoulder") || mapped.equals("back")
                    || mapped.equals("leftstick") || mapped.equals("rightstick");
            boolean acceptsTrigger = PAD_KEYS[i].equals("l") || PAD_KEYS[i].equals("r");
            if (!supported || (!acceptsTrigger && mapped.endsWith("trigger"))) {
                mapped = PAD_DEFAULTS[i];
            }
            boolean assignedHere = hasResolvedBindings
                    ? (resolved[resolvedIndex] & PAD_BITS[i]) != 0 : source.equals(mapped);
            if (!assignedHere) continue;
            if (assigned.length() != 0) assigned.append('/');
            String label = PAD_LABELS[i];
            if (matchLabels && i == 0) label = getResources().getString(R.string.touch_pass);
            if (matchLabels && i == 1) label = getResources().getString(R.string.touch_shoot);
            if (matchLabels && PAD_KEYS[i].equals("l")) label = getResources().getString(R.string.touch_lob);
            assigned.append(label);
        }
        control.label = assigned.length() == 0 ? "–" : assigned.toString();
    }

    private Control face(String label, float cx, float cy, float radius, int buttonMask) {
        Control c = new Control(label, Shape.CIRCLE);
        c.cx = cx; c.cy = cy; c.radius = radius; c.buttonMask = buttonMask;
        return c;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        for (Control c : controls) {
            final float targetPressure = c.pointerId >= 0
                    || (editing && positionKey(c).equals(selectedControl)) ? 1f : 0f;
            c.pressure += (targetPressure - c.pressure) * 0.35f;
            if (Math.abs(targetPressure - c.pressure) > 0.003f) postInvalidateOnAnimation();
            else c.pressure = targetPressure;

            if (metal) {
                switch (c.shape) {
                    case RECTANGLE: drawMetalRectangle(canvas, c); break;
                    case CIRCLE:    drawMetalHexagon(canvas, c);   break;
                    case STICK:     drawMetalStick(canvas, c);     break;
                }
                continue;
            }
            switch (c.shape) {
                case RECTANGLE: drawRectangle(canvas, c); break;
                case CIRCLE:    drawCircle(canvas, c);    break;
                case STICK:  drawStick(canvas, c);  break;
            }
        }
    }

    private int alpha(float value) {
        return Math.max(0, Math.min(255, Math.round(value * opacity)));
    }

    private void paintControl(Canvas canvas, Path shape, float pressure, int accent) {
        controlPaint.setColor(0xFF151D29);
        controlPaint.setAlpha(alpha(155));
        canvas.drawPath(shape, controlPaint);
        controlPaint.setColor(accent);
        controlPaint.setAlpha(alpha(22 + 62 * pressure));
        canvas.drawPath(shape, controlPaint);

        final float d = Math.max(1f, getResources().getDisplayMetrics().density);
        controlPaint.setStyle(Paint.Style.STROKE);
        controlPaint.setStrokeWidth(d * 2f);
        controlPaint.setColor(Color.BLACK);
        controlPaint.setAlpha(alpha(46));
        canvas.drawPath(shape, controlPaint);
        controlPaint.setStrokeWidth(d);
        controlPaint.setColor(accent);
        controlPaint.setAlpha(alpha(125 + 90 * pressure));
        canvas.drawPath(shape, controlPaint);
        controlPaint.setStyle(Paint.Style.FILL);
    }

    private void drawRectangle(Canvas canvas, Control c) {
        final float radius = c.bounds.height() * 0.42f;
        path.reset();
        path.addRoundRect(c.bounds, radius, radius, Path.Direction.CW);
        paintControl(canvas, path, c.pressure, c.role == Role.MENU ? SETTINGS_ACCENT : CONTROL_ACCENT);
        final float textSize = c.bounds.height() * (c.label.length() > 2 ? 0.30f : 0.42f);
        drawLabel(canvas, c.label, c.bounds.centerX(), c.bounds.centerY(), textSize, c.pressure);
    }

    private void drawCircle(Canvas canvas, Control c) {
        path.reset();
        path.addCircle(c.cx, c.cy, c.radius, Path.Direction.CW);
        paintControl(canvas, path, c.pressure, c.role == Role.MENU ? SETTINGS_ACCENT : CONTROL_ACCENT);
        if (c.role == Role.MENU) {
            drawSettingsIcon(canvas, c);
        } else {
            drawLabel(canvas, c.label, c.cx, c.cy, c.radius * 0.78f, c.pressure);
        }
    }

    private void drawSettingsIcon(Canvas canvas, Control c) {
        final float halfWidth = c.radius * 0.98f;
        final float d = Math.max(1f, getResources().getDisplayMetrics().density);
        final float[] knobOffsets = {-0.22f, 0.55f, -0.55f};

        controlPaint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            final float y = c.cy + (i - 1) * halfWidth * 0.62f;
            controlPaint.setStyle(Paint.Style.STROKE);
            controlPaint.setStrokeWidth(d * 1.6f);
            controlPaint.setColor(Color.WHITE);
            controlPaint.setAlpha(alpha(228 + 27 * c.pressure));
            canvas.drawLine(c.cx - halfWidth, y, c.cx + halfWidth, y, controlPaint);
            controlPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(c.cx + knobOffsets[i] * halfWidth, y, d * 2.6f, controlPaint);
        }
        controlPaint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawStick(Canvas canvas, Control c) {
        final boolean isLeftStick = c == leftStick;
        final float dx = isLeftStick ? leftStickDx : cStickDx;
        final float dy = isLeftStick ? leftStickDy : cStickDy;

        // Draw a dark outline so the stick remains visible over bright scenes.
        final float d1 = getResources().getDisplayMetrics().density;
        controlPaint.setStyle(Paint.Style.STROKE);
        controlPaint.setStrokeWidth(d1 * 2.2f);
        controlPaint.setColor(Color.BLACK);
        controlPaint.setAlpha(alpha(58));
        canvas.drawCircle(c.cx, c.cy, c.radius, controlPaint);
        controlPaint.setStrokeWidth(d1 * 1.2f);
        controlPaint.setColor(Color.WHITE);
        controlPaint.setAlpha(alpha(120));
        canvas.drawCircle(c.cx, c.cy, c.radius, controlPaint);
        controlPaint.setStyle(Paint.Style.FILL);

        final float maxTravel = c.radius * 0.52f;
        final float dist = (float) Math.hypot(dx, dy);
        final float k = dist > maxTravel ? maxTravel / dist : 1f;
        path.reset();
        path.addCircle(c.cx + dx * k, c.cy + dy * k, c.radius * 0.56f, Path.Direction.CW);
        paintControl(canvas, path, c.pressure, CONTROL_ACCENT);
        drawLabel(canvas, c.label, c.cx, c.cy, c.radius * 0.5f, c.pressure);
    }

    private void drawLabel(Canvas canvas, String s, float cx, float cy,
                          float textSize, float pressure) {
        drawLabel(canvas, s, cx, cy, textSize, Color.WHITE, alpha(216 + 39 * pressure));
    }

    private void drawLabel(Canvas canvas, String s, float cx, float cy, float textSize, int color,
                           int alphaValue) {
        if (s == null || s.isEmpty()) return;
        labelPaint.setTextSize(textSize);
        if (s.length() > 3) labelPaint.setTextSize(textSize * 3f / s.length());
        labelPaint.setColor(color);
        labelPaint.setAlpha(alphaValue);
        labelPaint.setShadowLayer(textSize * 0.18f, 0, textSize * 0.04f, 0x66000000);
        final Paint.FontMetrics fm = labelPaint.getFontMetrics();
        canvas.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, labelPaint);
        labelPaint.clearShadowLayer();
    }

    // A pointer stays with the control it pressed until release or cancellation.
    // ---- Metal style --------------------------------------------------------------------------

    private float rim(float size) {
        return Math.max(2f * getResources().getDisplayMetrics().density, size * 0.075f);
    }

    private static int blend(int from, int to, float t) {
        final float u = 1f - t;
        return Color.argb(255,
                Math.round(Color.red(from) * u + Color.red(to) * t),
                Math.round(Color.green(from) * u + Color.green(to) * t),
                Math.round(Color.blue(from) * u + Color.blue(to) * t));
    }

    // Chrome (or gold) across the box the shape sits in.
    private void fillMetal(Canvas canvas, Path shape, float left, float top, float width,
                           float height, Shader shader) {
        shaderMatrix.setScale(width, height);
        shaderMatrix.postTranslate(left, top);
        shader.setLocalMatrix(shaderMatrix);
        metalPaint.setShader(shader);
        metalPaint.setStyle(Paint.Style.FILL);
        metalPaint.setAlpha(alpha(255));
        canvas.drawPath(shape, metalPaint);
        metalPaint.setShader(null);
    }

    private void fillPanel(Canvas canvas, Path shape, float pressure) {
        metalPaint.setShader(null);
        metalPaint.setStyle(Paint.Style.FILL);
        metalPaint.setColor(blend(METAL_PANEL, METAL_ACCENT, pressure));
        metalPaint.setAlpha(alpha(230 + 25 * pressure));
        canvas.drawPath(shape, metalPaint);
    }

    // A pressed control glows in the accent colour behind its rim.
    private void glow(Canvas canvas, Path shape, float pressure) {
        if (pressure <= 0.01f) return;
        metalPaint.setShader(null);
        metalPaint.setStyle(Paint.Style.STROKE);
        metalPaint.setStrokeJoin(Paint.Join.ROUND);
        metalPaint.setStrokeWidth(rim(0f) * 3f);
        metalPaint.setColor(METAL_ACCENT);
        metalPaint.setAlpha(alpha(110 * pressure));
        canvas.drawPath(shape, metalPaint);
        metalPaint.setStyle(Paint.Style.FILL);
    }

    // Action words (Shoot, Pass, Lob) in the accent; letters in white; dark on a lit panel.
    private int metalLabelColor(String label, float pressure) {
        final boolean action = label != null
                && (label.equals(shootWord) || label.equals(passWord) || label.equals(lobWord));
        return blend(action ? METAL_ACCENT : Color.WHITE, METAL_PRESSED_TEXT, pressure);
    }

    // A flat-topped hexagon with circumradius r: two vertices on the horizontal axis.
    private static void hexagon(Path p, float cx, float cy, float r) {
        final float h = r * 0.8660254f;
        p.reset();
        p.moveTo(cx - r, cy);
        p.lineTo(cx - r * 0.5f, cy - h);
        p.lineTo(cx + r * 0.5f, cy - h);
        p.lineTo(cx + r, cy);
        p.lineTo(cx + r * 0.5f, cy + h);
        p.lineTo(cx - r * 0.5f, cy + h);
        p.close();
    }

    private void drawMetalHexagon(Canvas canvas, Control c) {
        final boolean menu = c.role == Role.MENU;
        final float r = c.radius;
        final float t = rim(r);
        hexagon(path, c.cx, c.cy, r);
        glow(canvas, path, c.pressure);
        fillMetal(canvas, path, c.cx - r, c.cy - r, 2f * r, 2f * r, menu ? GOLD : CHROME);
        // An inset of t from each edge takes t / cos 30° off the circumradius.
        hexagon(innerPath, c.cx, c.cy, r - t * 1.1547f);
        fillPanel(canvas, innerPath, menu ? 0f : c.pressure);
        if (menu) {
            drawSettingsIcon(canvas, c);
            return;
        }
        drawLabel(canvas, c.label, c.cx, c.cy, r * 0.62f, metalLabelColor(c.label, c.pressure),
                alpha(240 + 15 * c.pressure));
    }

    // Shoulders and the centre buttons lean outward, like the sides of a pitch seen in perspective.
    private void parallelogram(Path p, RectF b, boolean leansLeft) {
        final float slant = b.width() * 0.14f;
        p.reset();
        if (leansLeft) {
            p.moveTo(b.left + slant, b.top);
            p.lineTo(b.right, b.top);
            p.lineTo(b.right - slant, b.bottom);
            p.lineTo(b.left, b.bottom);
        } else {
            p.moveTo(b.left, b.top);
            p.lineTo(b.right - slant, b.top);
            p.lineTo(b.right, b.bottom);
            p.lineTo(b.left + slant, b.bottom);
        }
        p.close();
    }

    private final RectF innerBounds = new RectF();

    private void drawMetalRectangle(Canvas canvas, Control c) {
        final RectF b = c.bounds;
        final boolean leansLeft = b.centerX() < getWidth() / 2f;
        final float t = rim(b.height());
        parallelogram(path, b, leansLeft);
        glow(canvas, path, c.pressure);
        fillMetal(canvas, path, b.left, b.top, b.width(), b.height(), CHROME);
        innerBounds.set(b.left + t * 1.4f, b.top + t, b.right - t * 1.4f, b.bottom - t);
        parallelogram(innerPath, innerBounds, leansLeft);
        fillPanel(canvas, innerPath, c.pressure);
        // Sized to the panel's width rather than shrunk by letter count, so BACK and START read.
        float textSize = b.height() * 0.44f;
        if (c.label != null && !c.label.isEmpty()) {
            labelPaint.setTextSize(textSize);
            final float room = innerBounds.width() * 0.72f;
            final float width = labelPaint.measureText(c.label);
            if (width > room) textSize *= room / width;
            // drawLabel shrinks labels past three letters; undo that, the size already fits.
            if (c.label.length() > 3) textSize *= c.label.length() / 3f;
        }
        drawLabel(canvas, c.label, b.centerX(), b.centerY(), textSize,
                metalLabelColor(c.label, c.pressure), alpha(240 + 15 * c.pressure));
    }

    private void drawMetalStick(Canvas canvas, Control c) {
        final boolean isLeftStick = c == leftStick;
        final float dx = isLeftStick ? leftStickDx : cStickDx;
        final float dy = isLeftStick ? leftStickDy : cStickDy;
        final float r = c.radius;
        final float t = rim(r * 0.6f);

        // The well: a chrome ring around a darkened floor.
        path.reset();
        path.addCircle(c.cx, c.cy, r, Path.Direction.CW);
        fillMetal(canvas, path, c.cx - r, c.cy - r, 2f * r, 2f * r, CHROME);
        innerPath.reset();
        innerPath.addCircle(c.cx, c.cy, r - t, Path.Direction.CW);
        metalPaint.setShader(null);
        metalPaint.setStyle(Paint.Style.FILL);
        metalPaint.setColor(METAL_PANEL);
        metalPaint.setAlpha(alpha(150));
        canvas.drawPath(innerPath, metalPaint);

        // The knob: chrome rim, dark cap with a small hexagon, lit while held.
        final float maxTravel = r * 0.52f;
        final float dist = (float) Math.hypot(dx, dy);
        final float k = dist > maxTravel ? maxTravel / dist : 1f;
        final float kx = c.cx + dx * k, ky = c.cy + dy * k, kr = r * 0.56f;
        path.reset();
        path.addCircle(kx, ky, kr, Path.Direction.CW);
        glow(canvas, path, c.pressure);
        fillMetal(canvas, path, kx - kr, ky - kr, 2f * kr, 2f * kr, CHROME);
        innerPath.reset();
        innerPath.addCircle(kx, ky, kr - t, Path.Direction.CW);
        fillPanel(canvas, innerPath, c.pressure);
        if (c.label != null && !c.label.isEmpty()) {
            drawLabel(canvas, c.label, kx, ky, kr * 0.7f, metalLabelColor(c.label, c.pressure),
                    alpha(240));
        } else {
            hexagon(innerPath, kx, ky, kr * 0.42f);
            metalPaint.setStyle(Paint.Style.STROKE);
            metalPaint.setStrokeWidth(t * 0.8f);
            metalPaint.setColor(blend(METAL_LABEL_IDLE, METAL_PRESSED_TEXT, c.pressure));
            metalPaint.setAlpha(alpha(230));
            canvas.drawPath(innerPath, metalPaint);
            metalPaint.setStyle(Paint.Style.FILL);
        }
    }

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
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    move(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            }
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

    private static String positionKey(Control control) {
        return control.role.name() + "_" + control.buttonMask;
    }

    private int usableWidth() {
        return Math.max(1, getWidth() - leftInset - rightInset);
    }

    private int usableHeight() {
        return Math.max(1, getHeight() - topInset - bottomInset);
    }

    private void moveControl(Control control, float x, float y) {
        boolean rectangle = control.shape == Shape.RECTANGLE;
        float halfWidth = rectangle ? control.bounds.width() / 2f : control.radius;
        float halfHeight = rectangle ? control.bounds.height() / 2f : control.radius;
        x = Math.max(leftInset + halfWidth, Math.min(x, getWidth() - rightInset - halfWidth));
        y = Math.max(topInset + halfHeight, Math.min(y, getHeight() - bottomInset - halfHeight));
        if (rectangle) control.bounds.offsetTo(x - halfWidth, y - halfHeight);
        else {
            control.cx = x;
            control.cy = y;
        }
    }

    private boolean editTouch(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                for (int i = controls.size() - 1; i >= 0; --i) {
                    Control control = controls.get(i);
                    if (!control.contains(x, y)) continue;
                    dragging = control;
                    selectedControl = positionKey(control);
                    dragX = x - (control.shape == Shape.RECTANGLE ? control.bounds.centerX() : control.cx);
                    dragY = y - (control.shape == Shape.RECTANGLE ? control.bounds.centerY() : control.cy);
                    break;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging != null && event.getPointerCount() == 1) moveControl(dragging, x - dragX, y - dragY);
                break;
            case MotionEvent.ACTION_UP:
                if (dragging != null) {
                    float cx = dragging.shape == Shape.RECTANGLE ? dragging.bounds.centerX() : dragging.cx;
                    float cy = dragging.shape == Shape.RECTANGLE ? dragging.bounds.centerY() : dragging.cy;
                    String key = positionKey(dragging);
                    positions.edit()
                            .putFloat(key + "_x", (cx - leftInset) / usableWidth())
                            .putFloat(key + "_y", (cy - topInset) / usableHeight()).apply();
                    dragging = null;
                    performClick();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != null) layoutControls(getWidth(), getHeight());
                break;
        }
        invalidate();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void press(int pointerId, float x, float y) {
        for (int i = controls.size() - 1; i >= 0; --i) {
            Control c = controls.get(i);
            if (c.pointerId < 0 && c.contains(x, y)) {
                c.pointerId = pointerId;
                if (c.shape == Shape.STICK) move(pointerId, x, y);
                else if (!editing) tick();
                return;
            }
        }
    }

    // A short tick under the thumb, so a touch button feels pressed. Through the vibrator rather
    // than performHapticFeedback, which many phones turn off system-wide for the keyboard.
    private void tick() {
        if (!haptics || vibrator == null || !vibrator.hasVibrator()) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            vibrator.vibrate(android.os.VibrationEffect.createPredefined(
                    android.os.VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                    12, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void move(int pointerId, float x, float y) {
        for (Control c : controls) {
            if (c.pointerId == pointerId && c.shape == Shape.STICK) {
                if (c == leftStick) { leftStickDx = x - c.cx; leftStickDy = y - c.cy; }
                else             { cStickDx = x - c.cx; cStickDy = y - c.cy; }
            }
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
        for (Control control : controls) {
            control.pointerId = -1;
        }
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
                case BUTTON:       buttons |= c.buttonMask; break;
                case LEFT_TRIGGER: leftTrigger = 255;    break;
                case RIGHT_TRIGGER: rightTrigger = 255;    break;
                default: break;
            }
        }
        final int[] m = stickAxes(leftStick, leftStickDx, leftStickDy);
        final int[] c = stickAxes(cStick, cStickDx, cStickDy);
        TouchGamepad.sendState(buttons, leftTrigger, rightTrigger, m[0], m[1], c[0], c[1]);
    }

    // SDL stick Y increases downward. Apply the deadzone radially, then rescale the remaining travel.
    private int[] stickAxes(Control stick, float dx, float dy) {
        if (stick == null || stick.radius <= 0) return new int[]{0, 0};
        final float travel = stick.radius * 0.85f;
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
}
