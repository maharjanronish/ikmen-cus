# Patch: minimal touch controller for ikemen-droid

Complete diff applied on top of stock [Jesuszilla/ikemen-droid](https://github.com/Jesuszilla/ikemen-droid)
(base commit dbc5cfb).
Engine side is unmodified (upstream Ikemen-GO main @ e9bfe112).

diff --git a/app/build.gradle b/app/build.gradle
index 2f7ad8c..8c12165 100644
--- a/app/build.gradle
+++ b/app/build.gradle
@@ -26,7 +26,7 @@ android {
         minSdkVersion 21
         targetSdkVersion 30
         versionCode 1
-        versionName "1.0"
+        versionName "0.103-mobile-minimal"
         resourceConfigurations += ["en", "de", "es", "fr", "ja", "pt", "zh"]
 //        externalNativeBuild {
 //            ndkBuild {
diff --git a/app/src/main/AndroidManifest.xml b/app/src/main/AndroidManifest.xml
index 910fa0f..05c2149 100644
--- a/app/src/main/AndroidManifest.xml
+++ b/app/src/main/AndroidManifest.xml
@@ -7,9 +7,9 @@
     android:versionName="1.0"
     android:installLocation="auto">
 
-    <!-- Force Target 21 -->
-    <uses-sdk android:maxSdkVersion="21" android:targetSdkVersion="30" />
-
+<!-- min/target SDK come from app/build.gradle (AGP 8.1): minSdk 21, targetSdk 30.
+     Do NOT set android:maxSdkVersion here; a stale maxSdkVersion=21 used to block
+     installation on Android 6.0+ devices. -->
     <!-- OpenGL ES 3.2 -->
     <uses-feature android:glEsVersion="0x00030002" android:required="true" />
 
diff --git a/app/src/main/java/org/libsdl/app/ControllerOverlay.java b/app/src/main/java/org/libsdl/app/ControllerOverlay.java
index 1feb8a7..684bad8 100644
--- a/app/src/main/java/org/libsdl/app/ControllerOverlay.java
+++ b/app/src/main/java/org/libsdl/app/ControllerOverlay.java
@@ -15,20 +15,43 @@ import java.util.HashSet;
 import java.util.Map;
 import java.util.Set;
 
+/**
+ * Minimal gamepad-style virtual controller for Ikemen GO.
+ *
+ * Layout: D-pad + 4 face buttons (X/Y punches, A/B kicks) + HV heavy modifier
+ * + small Start/Pause buttons.
+ *
+ * HV modifier: while held, punches (X/Y) send Z (heavy punch) and kicks (A/B)
+ * send C (heavy kick). This keeps the on-screen button count low while every
+ * MUGEN attack stays reachable. The modifier can be held before or after the
+ * attack button is touched; held inputs are retranslated live.
+ */
 public class ControllerOverlay extends RelativeLayout {
     private int virtualDeviceId;
     private int hatX, hatY = 0;
     private final Map<Integer, Set<Integer>> pointerStates = new HashMap<>(); // PointerID -> Keycode/Axis
     private boolean isInitialized = false;
 
+    // ---- Button codes (Android keycodes, sent through SDLControllerManager) ----
+    private static final int CODE_A     = 96;    // A      -> light kick
+    private static final int CODE_B     = 97;    // B      -> medium kick
+    private static final int CODE_X     = 99;    // X      -> light punch
+    private static final int CODE_Y     = 100;   // Y      -> medium punch
+    private static final int CODE_Z     = 103;   // RB     -> heavy punch (via HV)
+    private static final int CODE_C     = 4005;  // RT ax. -> heavy kick (via HV)
+    private static final int CODE_START = 108;   // START
+    private static final int CODE_MENU  = 109;   // BACK   -> pause menu
+    private static final int CODE_HV    = 5000;  // local modifier, never sent to SDL
+
+    // HV modifier state and the code currently sent for each held face button
+    private boolean hvHeld = false;
+    private final Map<Integer, Integer> sentFaceCode = new HashMap<>();
+
     public int getPhysicalJoystickCount() {
         int count = 0;
         int[] deviceIds = InputDevice.getDeviceIds();
         for (int id : deviceIds) {
             InputDevice dev = InputDevice.getDevice(id);
-            // Check if the device is a physical joystick or gamepad
-            // OLD LOGIC: Checked only flags (Let sensors through)
-            // NEW LOGIC: Checks isRealGamepad (Stricter filtering by querying for buttons)
             if (isRealGamepad(dev)) {
                 count++;
             }
@@ -44,15 +67,9 @@ public class ControllerOverlay extends RelativeLayout {
         boolean isJoystick = ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) ||
                 ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD);
 
-        // Must identify as joystick/gamepad
         if (!isJoystick) return false;
-
-        // Ignore virtual devices (optional, prevents software loopbacks)
         if (device.isVirtual()) return false;
 
-        // Ghost filter, does it have actual buttons?
-        // Sensors report as Joysticks but have NO buttons.
-        // Real gamepads always have at least one of these.
         int[] keysToCheck = {
                 android.view.KeyEvent.KEYCODE_BUTTON_A,
                 android.view.KeyEvent.KEYCODE_BUTTON_B,
@@ -64,10 +81,10 @@ public class ControllerOverlay extends RelativeLayout {
 
         boolean[] hasKeys = device.hasKeys(keysToCheck);
         for (boolean exists : hasKeys) {
-            if (exists) return true; // It has any of these buttons, it's a real gamepad
+            if (exists) return true;
         }
 
-        return false; // No buttons, its a sensor, ignore it
+        return false;
     }
 
     @Override
@@ -79,112 +96,51 @@ public class ControllerOverlay extends RelativeLayout {
         return true;
     }
 
-    private int leftJoyPointerId = -1;
-    private int rightJoyPointerId = -1;
-
     @Override
     public boolean onTouchEvent(MotionEvent event) {
         int action = event.getActionMasked();
         int index = event.getActionIndex();
         int pId = event.getPointerId(index);
 
-        // Get raw coordinates for global hit detection
         float x = event.getX(index);
         float y = event.getY(index);
 
         if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
-            // Check Joysticks first
-            if (isViewAtLocation(findViewById(R.id.left_analog), x, y)) {
-                leftJoyPointerId = pId;
-            } else if (isViewAtLocation(findViewById(R.id.right_analog), x, y)) {
-                rightJoyPointerId = pId;
-            } else {
-                // If not a stick, it's a button/dpad
-                updatePointer(pId, getButtonsAt(x, y));
-            }
+            updatePointer(pId, getButtonsAt(x, y));
         } else if (action == MotionEvent.ACTION_MOVE) {
             for (int i = 0; i < event.getPointerCount(); i++) {
-                int movePId = event.getPointerId(i); // The stable ID
-
-                // Get coordinates specifically for THIS pointer index
+                int movePId = event.getPointerId(i);
                 float mx = event.getX(i);
                 float my = event.getY(i);
 
-                if (movePId == leftJoyPointerId) {
-                    updateJoystickLogic(findViewById(R.id.left_analog), mx, my);
-                } else if (movePId == rightJoyPointerId) {
-                    updateJoystickLogic(findViewById(R.id.right_analog), mx, my);
-                } else {
-                    Set<Integer> currentButtons = getButtonsAt(mx, my);
-                    Set<Integer> lastButtons = pointerStates.get(movePId);
-
-                    // If the sets are different, update the physical state
-                    if (!currentButtons.equals(lastButtons)) {
-                        updatePointer(movePId, currentButtons);
-                    }
+                Set<Integer> currentButtons = getButtonsAt(mx, my);
+                Set<Integer> lastButtons = pointerStates.get(movePId);
+
+                if (!currentButtons.equals(lastButtons)) {
+                    updatePointer(movePId, currentButtons);
                 }
             }
         } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
-            if (pId == leftJoyPointerId) {
-                resetJoystick(findViewById(R.id.left_analog));
-                leftJoyPointerId = -1;
-            } else if (pId == rightJoyPointerId) {
-                resetJoystick(findViewById(R.id.right_analog));
-                rightJoyPointerId = -1;
-            } else {
-                releasePointer(pId);
-            }
+            releasePointer(pId);
         }
 
         updateDpadState(event);
         return true;
     }
 
-    private void updateJoystickLogic(View v, float x, float y) {
-        if (!(v instanceof JoystickOverlay)) return;
-        JoystickOverlay joy = (JoystickOverlay) v;
-
-        // LOCAL CENTER CALCULATION
-        float centerX = v.getLeft() + (v.getWidth() / 2f);
-        float centerY = v.getTop() + (v.getHeight() / 2f);
-        float radius = v.getWidth() / 3f;
-
-        float dx = x - centerX;
-        float dy = y - centerY;
-        float distance = (float) Math.sqrt(dx * dx + dy * dy);
-
-        if (distance > radius) {
-            dx = (dx / distance) * radius;
-            dy = (dy / distance) * radius;
-        }
-
-        joy.updateVisualPos((v.getWidth()/2f) + dx, (v.getHeight()/2f) + dy);
-        joy.sendToSDL(dx / radius, dy / radius);
-    }
-
-    private void resetJoystick(View v) {
-        if (!(v instanceof JoystickOverlay)) return;
-        JoystickOverlay joy = (JoystickOverlay) v;
-        joy.updateVisualPos(v.getWidth()/2f, v.getHeight()/2f);
-        joy.sendToSDL(0, 0);
-    }
-
     private void updatePointer(int pointerId, Set<Integer> newCodes) {
         Set<Integer> oldCodes = pointerStates.get(pointerId);
         if (oldCodes == null) oldCodes = new HashSet<>();
-        // Find buttons that were RELEASED (in old set, not in new)
         for (Integer oldCode : oldCodes) {
             if (!newCodes.contains(oldCode)) {
                 handleInput(oldCode, false);
             }
         }
-        // Find buttons that were PRESSED (in new set, not in old)
         for (Integer newCode : newCodes) {
             if (!oldCodes.contains(newCode)) {
                 handleInput(newCode, true);
             }
         }
-        // Save the new state for this finger
         pointerStates.put(pointerId, newCodes);
     }
 
@@ -197,17 +153,71 @@ public class ControllerOverlay extends RelativeLayout {
         }
     }
 
+    private boolean isFaceCode(int code) {
+        return code == CODE_A || code == CODE_B || code == CODE_X || code == CODE_Y;
+    }
+
+    // While HV is held: punches -> Z (heavy punch), kicks -> C (heavy kick)
+    private int translateAttack(int faceCode) {
+        if (!hvHeld) return faceCode;
+        if (faceCode == CODE_X || faceCode == CODE_Y) return CODE_Z;
+        return CODE_C;
+    }
+
+    // Re-send held face buttons with the new HV state (HV pressed/released mid-hold)
+    private void retranslateHeldFaces() {
+        for (Map.Entry<Integer, Integer> e : new HashMap<>(sentFaceCode).entrySet()) {
+            int face = e.getKey();
+            int oldSent = e.getValue();
+            int newSent = translateAttack(face);
+            if (oldSent == newSent) continue;
+            sendCode(oldSent, false);
+            sendCode(newSent, true);
+            sentFaceCode.put(face, newSent);
+        }
+    }
+
     private void handleInput(final int code, boolean pressed) {
         if (code == -1) return;
 
-        if (code >= 4000) { // TRIGGERS
-            int axis = code - 4000;
-            SDLControllerManager.onNativeJoy(virtualDeviceId, axis, pressed ? 1.0f : 0.0f);
+        // HV modifier: local state only, plus live retranslation of held attacks
+        if (code == CODE_HV) {
+            if (hvHeld != pressed) {
+                hvHeld = pressed;
+                retranslateHeldFaces();
+            }
+            updateButtonVisual(CODE_HV, pressed);
+            return;
         }
-        else if (code >= 1000) {
-            // D-PAD visuals only (Logic remains in updateDpadState)
+
+        // Face buttons go through the HV translation layer
+        if (isFaceCode(code)) {
+            if (pressed) {
+                int send = translateAttack(code);
+                sentFaceCode.put(code, send);
+                sendCode(send, true);
+            } else {
+                Integer sent = sentFaceCode.remove(code);
+                if (sent != null) {
+                    sendCode(sent, false);
+                }
+            }
+            updateButtonVisual(code, pressed);
+            return;
         }
-        else { // STANDARD BUTTONS
+
+        // Everything else (Start / Pause)
+        sendCode(code, pressed);
+        updateButtonVisual(code, pressed);
+    }
+
+    private void sendCode(final int code, boolean pressed) {
+        if (code >= 4000) { // TRIGGERS (axis values)
+            int axis = code - 4000;
+            SDLControllerManager.onNativeJoy(virtualDeviceId, axis, pressed ? 1.0f : 0.0f);
+        } else if (code >= 1000) {
+            // D-PAD visuals only (logic lives in updateDpadState)
+        } else { // STANDARD BUTTONS
             if (pressed) {
                 SDLControllerManager.onNativeHat(virtualDeviceId, 0, hatX, hatY);
                 int result = SDLControllerManager.onNativePadDown(virtualDeviceId, code);
@@ -218,32 +228,22 @@ public class ControllerOverlay extends RelativeLayout {
                 SDLControllerManager.onNativePadUp(virtualDeviceId, code);
             }
         }
-
-        // Always update visuals
-        updateButtonVisual(code, pressed);
     }
 
     private void updateButtonVisual(int code, boolean pressed) {
         int viewId = -1;
         switch (code) {
-            case 96:  viewId = R.id.btn_a; break;
-            case 97:  viewId = R.id.btn_b; break;
-            case 99:  viewId = R.id.btn_x; break;
-            case 100: viewId = R.id.btn_y; break;
-            case 102: viewId = R.id.btn_d; break;
-            case 103: viewId = R.id.btn_z; break;
-            case 108: viewId = R.id.btn_start; break;
-            case 109: viewId = R.id.btn_back; break;
+            case CODE_A:     viewId = R.id.btn_a; break;
+            case CODE_B:     viewId = R.id.btn_b; break;
+            case CODE_X:     viewId = R.id.btn_x; break;
+            case CODE_Y:     viewId = R.id.btn_y; break;
+            case CODE_START: viewId = R.id.btn_start; break;
+            case CODE_MENU:  viewId = R.id.btn_menu; break;
+            case CODE_HV:    viewId = R.id.btn_hv; break;
             case 1001: viewId = R.id.dp_up; break;
             case 1002: viewId = R.id.dp_down; break;
             case 1003: viewId = R.id.dp_left; break;
             case 1004: viewId = R.id.dp_right; break;
-//            case 1005: viewId = R.id.dp_upleft; break;
-//            case 1006: viewId = R.id.dp_downright; break;
-//            case 1007: viewId = R.id.dp_downleft; break;
-//            case 1008: viewId = R.id.dp_upright; break;
-            case 4004: viewId = R.id.btn_w; break;
-            case 4005: viewId = R.id.btn_c; break;
         }
 
         if (viewId != -1) {
@@ -260,7 +260,6 @@ public class ControllerOverlay extends RelativeLayout {
             View container = findViewById(R.id.dpad_container);
             if (container == null) return;
 
-            // Use Local coordinates to match event.getX()
             float centerX = container.getLeft() + (container.getWidth() / 2f);
             float centerY = container.getTop() + (container.getHeight() / 2f);
             float radius = container.getWidth() / 2f;
@@ -271,38 +270,25 @@ public class ControllerOverlay extends RelativeLayout {
                 float x = event.getX(i);
                 float y = event.getY(i);
 
-                // Calculate distance from center (Pythagoras)
                 float dx = x - centerX;
                 float dy = y - centerY;
                 float distance = (float) Math.sqrt(dx * dx + dy * dy);
 
-                // Only process if the finger is actually touching the D-Pad area
-                // (with  a small "deadzone" in the middle so neutral is easy to hit)
+                // Only react if the finger is on the D-Pad area
+                // (with a small deadzone in the middle so neutral is easy to hit)
                 if (distance > (radius * 0.2f) && distance < (radius * 1.5f)) {
-                    // Calculate angle in degrees (0 to 360)
                     double angle = Math.toDegrees(Math.atan2(dy, dx));
                     if (angle < 0) angle += 360;
 
                     // Map angle to 8 directions with wider diagonals
-                    // Cardinals get 30 degrees, Diagonals get 60 degrees
-                    if (angle >= 345 || angle < 15)        newX = 1;  // Right (Narrower)
-                    else if (angle >= 15  && angle < 75)   { newX = 1; newY = 1; }   // Down-Right (Wider)
-                    else if (angle >= 75  && angle < 105)  newY = 1; // Down
-                    else if (angle >= 105 && angle < 165)  { newX = -1; newY = 1; }  // Down-Left
+                    if (angle >= 345 || angle < 15)        newX = 1;  // Right
+                    else if (angle >= 15  && angle < 75)   { newX = 1; newY = 1; }   // Down-Right
+                    else if (angle >= 75  && angle < 105)  newY = 1;  // Down
+                    else if (angle >= 105 && angle < 165)  { newX = -1; newY = 1; } // Down-Left
                     else if (angle >= 165 && angle < 195)  newX = -1; // Left
                     else if (angle >= 195 && angle < 255)  { newX = -1; newY = -1; } // Up-Left
                     else if (angle >= 255 && angle < 285)  newY = -1; // Up
-                    else if (angle >= 285 && angle < 345)  { newX = 1; newY = -1; }  // Up-Right
-
-                    // Map angle to 8 directions
-//                    if (angle >= 337.5 || angle < 22.5)   newX = 1;  // Right
-//                    else if (angle >= 22.5  && angle < 67.5)  { newX = 1; newY = 1; }   // Down-Right
-//                    else if (angle >= 67.5  && angle < 112.5) newY = 1; // Down
-//                    else if (angle >= 112.5 && angle < 157.5) { newX = -1; newY = 1; }  // Down-Left
-//                    else if (angle >= 157.5 && angle < 202.5) newX = -1; // Left
-//                    else if (angle >= 202.5 && angle < 247.5) { newX = -1; newY = -1; } // Up-Left
-//                    else if (angle >= 247.5 && angle < 292.5) newY = -1; // Up
-//                    else if (angle >= 292.5 && angle < 337.5) { newX = 1; newY = -1; }  // Up-Right
+                    else if (angle >= 285 && angle < 345)  { newX = 1; newY = -1; } // Up-Right
 
                     break; // One finger is enough for the D-pad
                 }
@@ -325,20 +311,17 @@ public class ControllerOverlay extends RelativeLayout {
     private Set<Integer> getButtonsAt(float x, float y) {
         Set<Integer> detectedButtons = new HashSet<>();
 
-        // Define a "padding" factor in pixels. This allows the thumb to trigger
-        // two buttons if it's in the gap between them.
-        int padding = dpToPx(16);
-
-        checkAndAdd(detectedButtons, R.id.btn_a, 96, x, y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_b, 97, x, y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_x, 99, x, y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_y, 100, x, y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_d, 102, x,y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_z, 103, x,y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_w, 4004, x,y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_c, 4005, x,y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_start, 108, x,y, padding);
-        checkAndAdd(detectedButtons, R.id.btn_back, 109, x,y, padding);
+        // Padding lets a thumb trigger two adjacent buttons (chords/slides).
+        int pad = dpToPx(16);
+        int padSmall = dpToPx(10);
+
+        checkAndAdd(detectedButtons, R.id.btn_a, CODE_A, x, y, pad);
+        checkAndAdd(detectedButtons, R.id.btn_b, CODE_B, x, y, pad);
+        checkAndAdd(detectedButtons, R.id.btn_x, CODE_X, x, y, pad);
+        checkAndAdd(detectedButtons, R.id.btn_y, CODE_Y, x, y, pad);
+        checkAndAdd(detectedButtons, R.id.btn_hv, CODE_HV, x, y, padSmall);
+        checkAndAdd(detectedButtons, R.id.btn_start, CODE_START, x, y, padSmall);
+        checkAndAdd(detectedButtons, R.id.btn_menu, CODE_MENU, x, y, padSmall);
 
         return detectedButtons;
     }
@@ -352,13 +335,11 @@ public class ControllerOverlay extends RelativeLayout {
         v.getLocationOnScreen(loc);
         this.getLocationOnScreen(parentLoc);
 
-        // Calculate coordinates with padding
         int left   = loc[0] - parentLoc[0] - pad;
         int top    = loc[1] - parentLoc[1] - pad;
-        int right  = left + v.getWidth() + pad*2;
-        int bottom = top + v.getHeight() + pad*2;
+        int right  = left + v.getWidth() + pad * 2;
+        int bottom = top + v.getHeight() + pad * 2;
 
-        // Add if in region + padding
         if (x >= left && x <= right && y >= top && y <= bottom) {
             set.add(code);
         }
@@ -371,24 +352,6 @@ public class ControllerOverlay extends RelativeLayout {
         findViewById(R.id.dp_right).setPressed(hX == 1);
     }
 
-    // Helper to check if a finger is inside a specific button's area
-    private boolean isViewAtLocation(View v, float x, float y) {
-        if (v == null || v.getVisibility() != View.VISIBLE) return false;
-
-        int[] vLoc = new int[2];
-        v.getLocationOnScreen(vLoc); // Global position of button
-
-        int[] parentLoc = new int[2];
-        this.getLocationOnScreen(parentLoc); // Global position of the overlay
-
-        // Get the position of the button RELATIVE to the overlay
-        int relativeLeft = vLoc[0] - parentLoc[0];
-        int relativeTop = vLoc[1] - parentLoc[1];
-
-        return x >= relativeLeft && x <= (relativeLeft + v.getWidth()) &&
-                y >= relativeTop && y <= (relativeTop + v.getHeight());
-    }
-
     public ControllerOverlay(Context context) {
         super(context);
         this.setClickable(true);
@@ -396,7 +359,6 @@ public class ControllerOverlay extends RelativeLayout {
         this.setEnabled(true);
         this.setMotionEventSplittingEnabled(true);
 
-        // Inflate the buttons
         LayoutInflater inflater = LayoutInflater.from(context);
         View vc = inflater.inflate(R.layout.virtual_controller, this, true);
         disableAllTouches(vc);
@@ -414,27 +376,24 @@ public class ControllerOverlay extends RelativeLayout {
     }
 
     public void initializeVirtualController() {
-        android.util.Log.i("ControllerOverlay", "DEBUG: initializing virtual joystick...");
+        android.util.Log.i("ControllerOverlay", "Initializing minimal virtual controller...");
         virtualDeviceId = getPhysicalJoystickCount();
         pointerStates.clear();
+        sentFaceCode.clear();
+        hvHeld = false;
         hatX = 0;
         hatY = 0;
-        android.util.Log.i("ControllerOverlay", String.format("DEBUG: virtualDeviceID = %d", virtualDeviceId));
+        android.util.Log.i("ControllerOverlay", String.format("virtualDeviceID = %d", virtualDeviceId));
         SDLControllerManager.nativeRemoveJoystick(virtualDeviceId);
         ensureJoystickAlive();
-        // Initialize the joysticks
-        JoystickOverlay ls = findViewById(R.id.left_analog);
-        ls.setAttrs(virtualDeviceId, 0, 1);
-
-        JoystickOverlay rs = findViewById(R.id.right_analog);
-        rs.setAttrs(virtualDeviceId, 2, 3);
 
+        // Pulse the axes once so SDL registers the virtual gamepad's axes
         for (int i = 0; i < 6; i++) {
             SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.00390625f);
             SDLControllerManager.onNativeJoy(virtualDeviceId, i, 0.0f);
         }
         SDLControllerManager.onNativeHat(virtualDeviceId, 0, 0, 0);
-        android.util.Log.i("ControllerOverlay", "DEBUG: virtual joystick initialized!");
+        android.util.Log.i("ControllerOverlay", "Minimal virtual controller ready!");
         isInitialized = true;
     }
 
diff --git a/app/src/main/res/drawable-v24/round_button_heavy.xml b/app/src/main/res/drawable-v24/round_button_heavy.xml
new file mode 100644
index 0000000..afdb077
--- /dev/null
+++ b/app/src/main/res/drawable-v24/round_button_heavy.xml
@@ -0,0 +1,32 @@
+<?xml version="1.0" encoding="utf-8"?>
+<selector xmlns:android="http://schemas.android.com/apk/res/android">
+    <item android:state_pressed="true">
+        <shape xmlns:android="http://schemas.android.com/apk/res/android"
+               android:shape="oval">
+            <solid android:color="#CCFF8F00" />
+
+            <stroke
+                android:width="2dp"
+                android:color="#CCFFECB3" />
+
+            <size
+                android:width="70dp"
+                android:height="70dp" />
+        </shape>
+    </item>
+
+    <item>
+        <shape xmlns:android="http://schemas.android.com/apk/res/android"
+               android:shape="oval">
+            <solid android:color="#88FF8F00" />
+
+            <stroke
+                android:width="2dp"
+                android:color="#AAFFECB3" />
+
+            <size
+                android:width="70dp"
+                android:height="70dp" />
+        </shape>
+    </item>
+</selector>
diff --git a/app/src/main/res/layout/virtual_controller.xml b/app/src/main/res/layout/virtual_controller.xml
index 5969521..9069d7b 100644
--- a/app/src/main/res/layout/virtual_controller.xml
+++ b/app/src/main/res/layout/virtual_controller.xml
@@ -1,159 +1,128 @@
 <?xml version="1.0" encoding="utf-8"?>
+<!-- Ikemen GO minimal controller layout.
+     Gamepad-style: D-pad (left) + 4 face buttons in diamond (right) + HV heavy modifier.
+     Hold HV: punches (X/Y) become Z (heavy punch), kicks (A/B) become C (heavy kick).
+     Start / Pause are small buttons tucked in the top-right corner. -->
 <RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
     xmlns:app="http://schemas.android.com/apk/res-auto"
     android:layout_width="match_parent"
     android:layout_height="match_parent"
     android:splitMotionEvents="true">
 
+    <!-- ==================== D-PAD (bottom-left) ==================== -->
     <RelativeLayout
         android:id="@+id/dpad_container"
-        android:layout_width="150dp"
-        android:layout_height="150dp"
+        android:layout_width="160dp"
+        android:layout_height="160dp"
         android:layout_alignParentBottom="true"
         android:layout_alignParentLeft="true"
-        android:layout_marginLeft="50dp"
-        android:layout_marginBottom="55dp"
+        android:layout_marginLeft="20dp"
+        android:layout_marginBottom="26dp"
         android:splitMotionEvents="true"
         android:background="@drawable/round_button"
-        android:backgroundTint="#88FFFFFF">
+        android:backgroundTint="#66FFFFFF">
 
         <Button android:id="@+id/dp_up" android:text="↑"
-            android:layout_width="50dp" android:layout_height="50dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="53dp" android:layout_height="53dp"
             android:layout_centerHorizontal="true" android:layout_alignParentTop="true"
-            android:alpha="0.5"
+            android:alpha="0.45"
             android:background="#00000000"/>
-<!--        <Button android:id="@+id/dp_upleft" android:text="↖"-->
-<!--            android:layout_width="50dp" android:layout_height="50dp"-->
-<!--            android:layout_centerHorizontal="true" android:layout_alignParentTop="true"-->
-<!--            android:layout_alignParentLeft="true"-->
-<!--            android:alpha="0.5" />-->
         <Button android:id="@+id/dp_left" android:text="←"
-            android:layout_width="50dp" android:layout_height="50dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="53dp" android:layout_height="53dp"
             android:layout_centerVertical="true" android:layout_alignParentLeft="true"
-            android:alpha="0.5"
+            android:alpha="0.45"
             android:background="#00000000"/>
-<!--        <Button android:id="@+id/dp_downleft" android:text="↙"-->
-<!--            android:layout_width="50dp" android:layout_height="50dp"-->
-<!--            android:layout_centerVertical="true" android:layout_alignParentLeft="true"-->
-<!--            android:layout_alignParentBottom="true"-->
-<!--            android:alpha="0.5"/>-->
         <Button android:id="@+id/dp_down" android:text="↓"
-            android:layout_width="50dp" android:layout_height="50dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="53dp" android:layout_height="53dp"
             android:layout_centerHorizontal="true" android:layout_alignParentBottom="true"
-            android:alpha="0.5"
+            android:alpha="0.45"
             android:background="#00000000"/>
-<!--        <Button android:id="@+id/dp_downright" android:text="↘"-->
-<!--            android:layout_width="50dp" android:layout_height="50dp"-->
-<!--            android:layout_centerHorizontal="true" android:layout_alignParentBottom="true"-->
-<!--            android:layout_alignParentRight="true"-->
-<!--            android:alpha="0.5"/>-->
         <Button android:id="@+id/dp_right" android:text="→"
-            android:layout_width="50dp" android:layout_height="50dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="53dp" android:layout_height="53dp"
             android:layout_centerVertical="true" android:layout_alignParentRight="true"
-            android:alpha="0.5"
+            android:alpha="0.45"
             android:background="#00000000"/>
-<!--        <Button android:id="@+id/dp_upright" android:text="↗"-->
-<!--            android:layout_width="50dp" android:layout_height="50dp"-->
-<!--            android:layout_centerVertical="true" android:layout_alignParentRight="true"-->
-<!--            android:layout_alignParentTop="true"-->
-<!--            android:alpha="0.5"/>-->
     </RelativeLayout>
 
-    <org.libsdl.app.JoystickOverlay
-        android:id="@+id/left_analog"
-        android:layout_width="125dp"
-        android:layout_height="125dp"
-        android:layout_toRightOf="@id/dpad_container"
-        android:layout_alignTop="@id/dpad_container"
-        android:layout_marginLeft="34dp"
-        app:axisX="0" app:axisY="1" />
-
+    <!-- ============ FACE BUTTONS DIAMOND (bottom-right) ============ -->
     <RelativeLayout
         android:id="@+id/button_grid"
-        android:layout_width="320dp"
-        android:layout_height="220dp"
+        android:layout_width="200dp"
+        android:layout_height="200dp"
         android:layout_alignParentRight="true"
         android:layout_alignParentBottom="true"
-        android:layout_marginRight="10dp"
-        android:layout_marginBottom="55dp"
+        android:layout_marginRight="18dp"
+        android:layout_marginBottom="26dp"
         android:splitMotionEvents="true">
 
-        <Button android:id="@+id/btn_w" android:text="W"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_alignParentRight="true"
-            android:layout_alignParentTop="true"
-            android:background="@drawable/round_button" />
-
-        <Button android:id="@+id/btn_z" android:text="Z"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_toLeftOf="@id/btn_w"
-            android:layout_marginRight="2dp"
-            android:background="@drawable/round_button" />
-
+        <!-- Y: medium punch (top) -->
         <Button android:id="@+id/btn_y" android:text="Y"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_toLeftOf="@id/btn_z"
-            android:layout_marginTop="25dp"
-            android:layout_marginRight="2dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="56dp" android:layout_height="56dp"
+            android:layout_centerHorizontal="true" android:layout_alignParentTop="true"
+            android:alpha="0.85"
             android:background="@drawable/round_button" />
 
+        <!-- X: light punch (left) -->
         <Button android:id="@+id/btn_x" android:text="X"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_toLeftOf="@id/btn_y"
-            android:layout_marginTop="45dp"
-            android:layout_marginRight="2dp"
-            android:background="@drawable/round_button" />
-
-        <Button android:id="@+id/btn_d" android:text="D"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_alignParentRight="true"
-            android:layout_below="@id/btn_w"
-            android:layout_marginTop="12dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="56dp" android:layout_height="56dp"
+            android:layout_alignParentLeft="true" android:layout_centerVertical="true"
+            android:alpha="0.85"
             android:background="@drawable/round_button" />
 
-        <Button android:id="@+id/btn_c" android:text="C"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_toLeftOf="@id/btn_d"
-            android:layout_alignTop="@id/btn_d"
-            android:layout_marginRight="2dp"
+        <!-- A: light kick (right) -->
+        <Button android:id="@+id/btn_a" android:text="A"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="56dp" android:layout_height="56dp"
+            android:layout_alignParentRight="true" android:layout_centerVertical="true"
+            android:alpha="0.85"
             android:background="@drawable/round_button" />
 
+        <!-- B: medium kick (bottom) -->
         <Button android:id="@+id/btn_b" android:text="B"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_toLeftOf="@id/btn_c"
-            android:layout_alignTop="@id/btn_c"
-            android:layout_marginTop="20dp"
-            android:layout_marginRight="2dp"
-            android:background="@drawable/round_button" />
-
-        <Button android:id="@+id/btn_a" android:text="A"
-            android:layout_width="55dp" android:layout_height="55dp"
-            android:layout_toLeftOf="@id/btn_b"
-            android:layout_alignTop="@id/btn_b"
-            android:layout_marginTop="28dp"
-            android:layout_marginRight="2dp"
+            android:textSize="20sp" android:textStyle="bold" android:textColor="#FFFFFF"
+            android:layout_width="56dp" android:layout_height="56dp"
+            android:layout_centerHorizontal="true" android:layout_alignParentBottom="true"
+            android:alpha="0.85"
             android:background="@drawable/round_button" />
     </RelativeLayout>
 
-    <org.libsdl.app.JoystickOverlay
-        android:id="@+id/right_analog"
-        android:layout_width="125dp"
-        android:layout_height="125dp"
-        android:layout_below="@id/button_grid"
-        android:layout_alignParentRight="true"
-        android:layout_marginRight="34dp"
-        android:layout_marginTop="-120dp"
-        app:axisX="2" app:axisY="3" />
+    <!-- ============ HV: heavy attack modifier (hold) ============ -->
+    <Button android:id="@+id/btn_hv" android:text="HV"
+        android:textSize="17sp" android:textStyle="bold" android:textColor="#FFFFFF"
+        android:layout_width="50dp" android:layout_height="50dp"
+        android:layout_toLeftOf="@id/button_grid"
+        android:layout_alignTop="@id/button_grid"
+        android:layout_marginTop="75dp"
+        android:layout_marginRight="16dp"
+        android:alpha="0.9"
+        android:background="@drawable/round_button_heavy" />
 
+    <!-- ============ Start / Pause (top-right, small) ============ -->
     <LinearLayout
         android:layout_width="wrap_content"
         android:layout_height="wrap_content"
-        android:layout_centerHorizontal="true"
-        android:layout_alignParentBottom="true"
-        android:layout_marginBottom="20dp"
+        android:layout_alignParentTop="true"
+        android:layout_alignParentRight="true"
+        android:layout_marginTop="16dp"
+        android:layout_marginRight="16dp"
         android:splitMotionEvents="true">
-        <Button android:clickable="true" android:focusable="true" android:id="@+id/btn_back" android:text="Menu" android:layout_width="55dp" android:layout_height="55dp" android:background="@drawable/round_button" android:layout_margin="5dp" />
-        <Button android:clickable="true" android:focusable="true" android:id="@+id/btn_start" android:text="Start" android:layout_width="55dp" android:layout_height="55dp" android:background="@drawable/round_button" android:layout_margin="5dp" />
+        <Button android:id="@+id/btn_menu" android:text="Pause"
+            android:textSize="12sp" android:textColor="#FFFFFF"
+            android:layout_width="46dp" android:layout_height="46dp"
+            android:alpha="0.6"
+            android:background="@drawable/round_button" />
+        <Button android:id="@+id/btn_start" android:text="Start"
+            android:textSize="12sp" android:textColor="#FFFFFF"
+            android:layout_width="46dp" android:layout_height="46dp"
+            android:layout_marginStart="12dp"
+            android:alpha="0.6"
+            android:background="@drawable/round_button" />
     </LinearLayout>
 
-</RelativeLayout>
\ No newline at end of file
+</RelativeLayout>
