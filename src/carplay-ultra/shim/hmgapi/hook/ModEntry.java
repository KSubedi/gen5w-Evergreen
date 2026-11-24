/* SPDX-License-Identifier: GPL-3.0-or-later
 * Ultimate Wireless Projection: HMG-API's ModEntry, as CarPlay Ultra-Lite extends it.
 * Only appLoaded is ever called (from DiPO's CarPlayService.onCreate); zygoteInit would start the
 * volume-key experiment in system_server, which this port leaves out.
 */
package hmgapi.hook;

public abstract class ModEntry {
    public void zygoteInit(ModContext mod) throws Throwable {
    }

    public void appLoaded(ModContext mod, LoadedApp app) throws Throwable {
    }

    public void onSettingsChanged(ModContext mod) throws Throwable {
    }

    public void onEnabled(ModContext mod, boolean enabled) throws Throwable {
    }
}
