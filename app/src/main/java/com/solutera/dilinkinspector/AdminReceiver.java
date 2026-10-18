// AdminReceiver.java — DeviceAdminReceiver so the app can be set as device owner via `dpm set-device-owner`.
package com.solutera.dilinkinspector;

import android.app.admin.DeviceAdminReceiver;

/** Minimal device-admin component. Its presence lets an authorized shell promote this app to device owner
 *  (dpm set-device-owner com.solutera.dilinkinspector/.AdminReceiver), which grants broad management
 *  authority used only for local diagnostics on the owner's own device. No policies are enforced here. */
public final class AdminReceiver extends DeviceAdminReceiver {
}
