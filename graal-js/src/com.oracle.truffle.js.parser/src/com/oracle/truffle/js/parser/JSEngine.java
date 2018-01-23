/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.parser;

import java.util.concurrent.atomic.AtomicReference;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.js.builtins.JSDefaultBuiltinLookup;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSContextOptions;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.builtins.JSFunctionLookup;

public final class JSEngine {
    private static final JSEngine INSTANCE = new JSEngine();

    private final JSFunctionLookup functionLookup;
    private final JSParser parser;

    private JSEngine() {
        this.functionLookup = new JSDefaultBuiltinLookup();
        this.parser = new GraalJSEvaluator();
    }

    public static JSEngine getInstance() {
        return INSTANCE;
    }

    public Evaluator getEvaluator() {
        return parser;
    }

    public JSFunctionLookup getFunctionLookup() {
        return functionLookup;
    }

    public JSParser getParser() {
        return parser;
    }

    public JSContext createContext(JavaScriptLanguage language, TruffleLanguage.Env env) {
        return createContext(language, new GraalJSParserOptions(), env);
    }

    public JSRealm createRealm(JavaScriptLanguage language, TruffleLanguage.Env env) {
        return createContext(language, env).createRealm();
    }

    public JSContext createContext(JavaScriptLanguage language, GraalJSParserOptions parserOptions, TruffleLanguage.Env env) {
        JSContextOptions contextOptions = new JSContextOptions(parserOptions);
        return JSContext.createContext(parser, functionLookup, contextOptions, language, env);
    }

    public JSContext createContext(JavaScriptLanguage language, JSContextOptions contextOptions, TruffleLanguage.Env env) {
        return JSContext.createContext(parser, functionLookup, contextOptions, language, env);
    }

    public static JavaScriptLanguage createLanguage() {
        return new JavaScriptLanguage();
    }

    public static JSContext createJSContext() {
        return createJSContextAndRealm(null, null);
    }

    private static JSContext createJSContextAndRealm(JavaScriptLanguage language, TruffleLanguage.Env env) {
        return JSEngine.getInstance().createRealm(language, env).getContext();
    }

    public static JSContext createJSContext(JavaScriptLanguage language, TruffleLanguage.Env env) {
        if (JSTruffleOptions.PrepareFirstContext && JSContextOptions.optionsAllowPreInitializedContext(env)) {
            JSContext result = FirstContextHolder.firstContext.getAndSet(null);
            if (result != null) {
                result.setLanguage(language);
                result.setTruffleLanguageEnv(env);
                return result;
            }
        }

        return JSEngine.getInstance().createContext(language, env);
    }

    /*
     * Separate class for lazy loading: firstContext is only built when option is turned on and this
     * class gets loaded.
     */
    private static class FirstContextHolder {
        static final AtomicReference<JSContext> firstContext = new AtomicReference<>(createJSContextAndRealm(null, null));
    }
}