/* SPDX-License-Identifier: GPL-3.0-or-later
 * Ultimate Wireless Projection: HMG-API's LoadedApp, as CarPlay Ultra-Lite reads it.
 */
package hmgapi.hook;

import android.content.Context;

public final class LoadedApp {
    public final String packageName;
    public final String processName;
    public final ClassLoader classLoader;
    public final Context context;

    public LoadedApp(String packageName, String processName, ClassLoader classLoader, Context context) {
        this.packageName = packageName;
        this.processName = processName;
        this.classLoader = classLoader;
        this.context = context;
    }
}
