package org.jenkinsci.plugins.workflow.cps;

import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AbstractWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;

import java.lang.reflect.Method;

/**
 * {@link Whitelist} implementation for CPS flow execution.
 *
 * @author Kohsuke Kawaguchi
 */
class CpsWhitelist extends AbstractWhitelist {
    private CpsWhitelist() {}

    @Override
    public boolean permitsMethod(Method method, Object receiver, Object[] args) {
        // CpsScript dispatches to the DSL class
        if (receiver instanceof CpsScript && method.getName().equals("invokeMethod"))
            return true;

        // evaluate() family of methods are reimplemented in CpsScript for safe manner
        // but we can't allow arbitrary Script.evaluate() calls as that will escape sandbox
        if (receiver instanceof CpsScript && method.getName().equals("evaluate"))
            return true;

        return false;
    }

    /**
     * Stuff we whitelist specifically for CPS, with the rest of the installed rules combined.
     */
    private static Whitelist INSTANCE;

    public static synchronized Whitelist get() {
        if (INSTANCE==null) {
            INSTANCE = new ProxyWhitelist(new CpsWhitelist(),Whitelist.all());
        }
        return INSTANCE;
    }
}
