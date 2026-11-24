/* SPDX-License-Identifier: GPL-3.0-or-later
 * Ultimate Wireless Projection: the part of HMG-API's hook API that CarPlay Ultra-Lite uses,
 * written for a statically patched DiPO. There is no runtime hooking here: our DiPO build calls
 * Hooks.before/after from the methods CarPlay Ultra-Lite asks for (tools/build-carplay-ultra.py).
 */
package hmgapi.hook;

import java.lang.reflect.Member;

public abstract class MethodHook {
    protected void beforeHookedMethod(Param param) throws Throwable {
    }

    protected void afterHookedMethod(Param param) throws Throwable {
    }

    public static final class Param {
        public final Member method;
        public final Object thisObject;
        public final Object[] args;
        private Object result;
        private Throwable throwable;
        boolean returnEarly;

        Param(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        /** Recorded only: our call sites can't skip the original method (no hook asks to, see Hooks). */
        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
}
