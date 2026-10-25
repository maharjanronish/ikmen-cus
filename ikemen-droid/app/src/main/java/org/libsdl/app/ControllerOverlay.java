package org.libsdl.app;

import android.content.Context;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import org.ikemen_engine.ikemen_go.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal gamepad-style virtual controller for Ikemen GO.
 *
 * Layout: D-pad + 4 face buttons (X/Y punches, A/B kicks) + HV heavy modifier
 * + small Start/Pause buttons.
 *
 * HV modifier: while held, punches (X/Y) send Z (heavy punch) and kicks (A/B)
 * send C (heavy kick). This keeps the on-screen button count low while every
 * MUGEN attack stays reachable. The modifier can be held before or after the
 * attack button is touched; held inputs are retranslated live.
 */
public class ControllerOverlay extends RelativeLayout {
    private int virtualDeviceId;
    private int hatX, hatY = 0;
    private final Map<Integer, Set<Integer>> pointerStates = new HashMap<>(); // PointerID -> Keycode/Axis
    private boolean isInitialized = false;

    // ---- Button codes (Android keycodes, sent through SDLControllerManager) ----
    private static final int CODE_A     = 96;    // A      -> light kick
    private static final int CODE_B     = 97;    // B      -> medium kick
    private static final int CODE_X     = 99;    // X      -> light punch
    private static final int CODE_Y     = 100;   // Y      -> medium punch
    private static final int CODE_Z     = 103;   // RB     -> heavy punch (via HV)
    private static final int CODE_C     = 4005;  // RT ax. -> heavy kick (via HV)
    private static final int CODE_START = 108;   // START
    private static final int CODE_MENU  = 109;   // BACK   -> pause menu
    private static final int CODE_HV    = 5000;  // local modifier, never sent to SDL

    // HV modifier state and the code currently sent for each held face button
    private boolean hvHeld = false;
    private final Map<Integer, Integer> sentFaceCode = new HashMap<>();

    public int getPhysicalJoystickCount() {
        int count = 0;
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int id : deviceIds) {
            InputDevice dev = InputDevice.getDevice(id);
            if (isRealGamepad(dev)) {
                count++;
            }
        }
        return count;
    }

    // Helper to distinguish real controllers from "Ghost" sensors
    private boolean isRealGamepad(InputDevice device) {
        if (device == null) return false;

        int sources = device.getSources();
        boolean isJoystick = ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) ||
                ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);

        if (!isJoystick) return false;
        if (device.isVirtual()) return false;

        int[] keysToCheck = {
                android.view.KeyEvent.KEYCODE_BUTTON_A,
                android.view.KeyEvent.KEYCODE_BUTTON_B,
                android.view.KeyEvent.KEYCODE_BUTTON_X,
                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                android.view.KeyEvent.KEYCODE_BUTTON_START,
                android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
        };

        boolean[] hasKeys = device.hasKeys(keysToCheck);
        for (boolean exists : hasKeys) {
            if (exists) return true;
        }

        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isInitialized) {
            initializeVirtualController();
            isInitialized = true;
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pId = event.getPointerId(index);

        float x = event.getX(index);
        float y = event.getY(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            updatePointer(pId, getButtonsAt(x, y));
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int movePId = event.getPointerId(i);
                float mx = event.getX(i);
                float my = event.getY(i);

                Set<Integer> currentButtons = getButtonsAt(mx, my);
                Set<Integer> lastButtons = pointerStates.get(movePId);

                if (!currentButtons.equals(lastButtons)) {
                    updatePointer(movePId, currentButtons);
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            releasePointer(pId);
        }

        updateDpadState(event);
        return true;
    }

    private void updatePointer(int pointerId, Set<Integer> newCodes) {
        Set<Integer> oldCodes = pointerStates.get(pointerId);
        if (oldCodes == null) oldCodes = new HashSet<>();
        for (Integer oldCode : oldCodes) {
            if (!newCodes.contains(oldCode)) {
                handleInput(oldCode, false);
            }
        }
        for (Integer newCode : newCodes) {
            if (!oldCodes.contains(newCode)) {
                handleInput(newCode, true);
            }
        }
        pointerStates.put(pointerId, newCodes);
    }

    private void releasePointer(int pointerId) {
        Set<Integer> lastCodes = pointerStates.remove(pointerId);
        if (lastCodes != null) {
            for (Integer code : lastCodes) {
                handleInput(code, false);
            }
        }
    }

    private boolean isFaceCode(int code) {
        return code == CODE_A || code == CODE_B || code == CODE_X || code == CODE_Y;
    }

    // While HV is held: punches -> Z (heavy punch), kicks -> C (heavy kick)
    private int translateAttack(int faceCode) {
        if (!hvHeld) return faceCode;
        if (faceCode == CODE_X || faceCode == CODE_Y) return CODE_Z;
        return CODE_C;
    }

    // Re-send held face buttons with the new HV state (HV pressed/released mid-hold)
    private void retranslateHeldFaces() {
        for (Map.Entry<Integer, Integer> e : new HashMap<>(sentFaceCode).entrySet()) {
            int face = e.getKey();
            int oldSent = e.getValue();
            int newSent = translateAttack(face);
            if (oldSent == newSent) continue;
            sendCode(oldSent, false);
            sendCode(newSent, true);
            sentFaceCode.put(face, newSent);
        }
    }

    private void handleInput(final int code, boolean pressed) {
        if (code == -1) return;

        // HV modifier: local state only, plus live retranslation of held attacks
        if (code == CODE_HV) {
            if (hvHeld != pressed) {
                hvHeld = pressed;
                retranslateHeldFaces();
            }
            updateButtonVisual(CODE_HV, pressed);
            return;
        }

        // Face buttons go through the HV translation layer
        if (isFaceCode(code)) {
            if (pressed) {
                int send = translateAttack(code);
                sentFaceCode.put(code, send);
                sendCode(send, true);
            } else {
                Integer sent = sentFaceCode.remove(code);
                if (sent != null) {
                    sendCode(sent, false);
                }
            }
            updateButtonVisual(code, pressed);
            return;
        }

        // Everything else (Start / Pause)
        sendCode(code, pressed);
        updateButtonVisual(code, pressed);
    }

    private void sendCode(final int code, boolean pressed) {
        if (code >= 4000) { // TRIGGERS (axis values)
            int axis = code - 4000;
            SDLControllerManager.onNativeJoy(virtualDeviceId, axis, pressed ? 1.0f : 0.0f);
        } else if (code >= 1000) {
            // D-PAD visuals only (logic lives in updateDpadState)
        } else { // STANDARD BUTTONS
            if (pressed) {
                SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
                int result = SDLControllerManager.onNativePadDown(virtualDeviceId, code);
                if (result < 0) {
                    android.util.Log.e("SDL", "INPUT FAILURE: Device " + virtualDeviceId + " rejected button " + code);
                }
            } else {
                SDLControllerManager.onNativePadUp(virtualDeviceId, code);
            }
        }
    }

    private void updateButtonVisual(int code, boolean pressed) {
        int viewId = -1;
        switch (code) {
            case CODE_A:     viewId = R.id.btn_a; break;
            case CODE_B:     viewId = R.id.btn_b; break;
            case CODE_X:     viewId = R.id.btn_x; break;
            case CODE_Y:     viewId = R.id.btn_y; break;
            case CODE_START: viewId = R.id.btn_start; break;
            case CODE_MENU:  viewId = R.id.btn_menu; break;
            case CODE_HV:    viewId = R.id.btn_hv; break;
            case 1001: viewId = R.id.dp_up; break;
            case 1002: viewId = R.id.dp_down; break;
            case 1003: viewId = R.id.dp_left; break;
            case 1004: viewId = R.id.dp_right; break;
        }

        if (viewId != -1) {
            View v = findViewById(viewId);
            if (v != null) v.setPressed(pressed);
        }
    }

    private void updateDpadState(MotionEvent event) {
        int newX = 0, newY = 0;
        int action = event.getActionMasked();

        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            View container = findViewById(R.id.dpad_container);
            if (container == null) return;

            float centerX = container.getLeft() + (container.getWidth() / 2f);
            float centerY = container.getTop() + (container.getHeight() / 2f);
            float radius = container.getWidth() / 2f;

            for (int i = 0; i < event.getPointerCount(); i++) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex()) continue;

                float x = event.getX(i);
                float y = event.getY(i);

                float dx = x - centerX;
                float dy = y - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                // Only react if the finger is on the D-Pad area
                // (with a small deadzone in the middle so neutral is easy to hit)
                if (distance > (radius * 0.2f) && distance < (radius * 1.5f)) {
                    double angle = Math.toDegrees(Math.atan2(dy, dx));
                    if (angle < 0) angle += 360;

                    // Map angle to 8 directions with wider diagonals
                    if (angle >= 345 || angle < 15)        newX = 1;  // Right
                    else if (angle >= 15  && angle < 75)   { newX = 1; newY = 1; }   // Down-Right
                    else if (angle >= 75  && angle < 105)  newY = 1;  // Down
                    else if (angle >= 105 && angle < 165)  { newX = -1; newY = 1; } // Down-Left
                    else if (angle >= 165 && angle < 195)  newX = -1; // Left
                    else if (angle >= 195 && angle < 255)  { newX = -1; newY = -1; } // Up-Left
                    else if (angle >= 255 && angle < 285)  newY = -1; // Up
                    else if (angle >= 285 && angle < 345)  { newX = 1; newY = -1; } // Up-Right

                    break; // One finger is enough for the D-pad
                }
            }
        }

        if (newX != hatX || newY != hatY) {
            hatX = newX;
            hatY = newY;
            SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
            updateDpadVisual(hatX, hatY);
        }
    }

    private int dpToPx(int dp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    private Set<Integer> getButtonsAt(float x, float y) {
        Set<Integer> detectedButtons = new HashSet<>();

        // Padding lets a thumb trigger two adjacent buttons (chords/slides).
        int pad = dpToPx(16);
        int padSmall = dpToPx(10);

        checkAndAdd(detectedButtons, R.id.btn_a, CODE_A, x, y, pad);
        checkAndAdd(detectedButtons, R.id.btn_b, CODE_B, x, y, pad);
        checkAndAdd(detectedButtons, R.id.btn_x, CODE_X, x, y, pad);
        checkAndAdd(detectedButtons, R.id.btn_y, CODE_Y, x, y, pad);
        checkAndAdd(detectedButtons, R.id.btn_hv, CODE_HV, x, y, padSmall);
        checkAndAdd(detectedButtons, R.id.btn_start, CODE_START, x, y, padSmall);
        checkAndAdd(detectedButtons, R.id.btn_menu, CODE_MENU, x, y, padSmall);

        return detectedButtons;
    }

    private void checkAndAdd(Set<Integer> set, int viewId, int code, float x, float y, int pad) {
        View v = findViewById(viewId);
        if (v == null) return;

        int[] loc = new int[2];
        int[] parentLoc = new int[2];
        v.getLocationOnScreen(loc);
        this.getLocationOnScreen(parentLoc);

        int left   = loc[0] - parentLoc[0] - pad;
        int top    = loc[1] - parentLoc[1] - pad;
        int right  = left + v.getWidth() + pad * 2;
        int bottom = top + v.getHeight() + pad * 2;

        if (x >= left && x <= right && y >= top && y <= bottom) {
            set.add(code);
        }
    }

    private void updateDpadVisual(int hX, int hY) {
        findViewById(R.id.dp_up).setPressed(hY == -1);
        findViewById(R.id.dp_down).setPressed(hY == 1);
        findViewById(R.id.dp_left).setPressed(hX == -1);
        findViewById(R.id.dp_right).setPressed(hX == 1);
    }

    public ControllerOverlay(Context context) {
        super(context);
        this.setClickable(true);
        this.setFocusable(true);
        this.setEnabled(true);
        this.setMotionEventSplittingEnabled(true);

        LayoutInflater inflater = LayoutInflater.from(context);
        View vc = inflater.inflate(R.layout.virtual_controller, this, true);
        disableAllTouches(vc);
    }

    private void disableAllTouches(View v) {
        v.setClickable(false);
        v.setFocusable(false);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableAllTouches(vg.getChildAt(i));
            }
        }
    }

    public void initializeVirtualController() {
        android.util.Log.i("ControllerOverlay", "Initializing minimal virtual controller...");
        virtualDeviceId = getPhysicalJoystickCount();
        pointerStates.clear();
        sentFaceCode.clear();
        hvHeld = false;
        hatX = 0;
        hatY = 0;
        android.util.Log.i("ControllerOverlay", String.format("virtualDeviceID = %d", virtualDeviceId));
        SDLControllerManager.nativeRemoveJoystick(virtualDeviceId);
        ensureJoystickAlive();

        // Pulse the axes once so SDL registers the virtual gamepad's axes
        for (int i = 0; i < 6; i++) {
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.00390625f);
            SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.0f);
        }
        SDLControllerManager.onNativeHat(virtualDeviceId, 0, 0, 0);
        android.util.Log.i("ControllerOverlay", "Minimal virtual controller ready!");
        isInitialized = true;
    }

    private int ensureJoystickAlive() {
        return SDLControllerManager.nativeAddJoystick(virtualDeviceId, "Xbox 360 Controller", "Gamepad", 0x045E, 0x028E, false, 0xFFF, 6, 0x3F, 1, 0);
    }
}
