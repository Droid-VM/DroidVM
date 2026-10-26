/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.crosvm;

import android.crosvm.DisplayConfig;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

/**
 * Service to provide Crosvm with an Android Surface for showing a guest's display.
 *
 * NOTE: This binder is implemented by crosvm itself (android_display_backend). The method order
 * here MUST match the .aidl crosvm was built against, because binder transaction codes are derived
 * from declaration order. Input forwarding is NOT done through this interface; input goes through
 * per-device unix sockets that crosvm reads via `--input ...[path=...]`.
 */
interface ICrosvmAndroidDisplayService {
    void setSurface(in Surface surface, boolean forCursor);
    void setCursorStream(in ParcelFileDescriptor stream);
    void removeSurface(boolean forCursor);
    void saveFrameForSurface(boolean forCursor);
    void drawSavedFrameForSurface(boolean forCursor);

    /**
     * Returns the guest display configuration, or null if not available yet. crosvm's current
     * backend may not implement this; callers must tolerate the call failing and fall back to a
     * default resolution.
     */
    DisplayConfig getDisplayConfig();

    /**
     * Turn or flip the pictures queued to a surface: an ANATIVEWINDOW_TRANSFORM_* value (0 none,
     * 4 a quarter turn clockwise, 3 a half turn, 7 three quarters). The guest's buffers keep their
     * size; with a quarter turn the picture is as wide as they are tall, and the Surface's requested
     * size must follow. Holds across setSurface. A crosvm built before it fails the call.
     */
    void setSurfaceTransform(int transform, boolean forCursor);
}
