/*
 * Copyright (c) 2012, 2014, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.function;

import java.util.Map;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.nodes.FrameDescriptorProvider;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.NodeFactory;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.JavaScriptRealmBoundaryRootNode;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData.Target;

@NodeInfo(cost = NodeCost.NONE, language = "JavaScript", description = "The root node of all functions in JavaScript.")
public class FunctionRootNode extends JavaScriptRealmBoundaryRootNode implements FrameDescriptorProvider, JSFunctionData.CallTargetInitializer {

    @Child private JavaScriptNode body;

    private JSFunctionData functionData;
    private String internalFunctionName;

    protected FunctionRootNode(AbstractBodyNode body, FrameDescriptor frameDescriptor, JSFunctionData functionData, SourceSection sourceSection, String internalFunctionName) {
        super(functionData.getContext().getLanguage(), sourceSection, frameDescriptor);
        this.body = body;
        this.body.addRootTag();
        if (!this.body.hasSourceSection()) {
            this.body.setSourceSection(sourceSection);
        }
        this.functionData = functionData;
        this.internalFunctionName = internalFunctionName;
    }

    public static FunctionRootNode create(AbstractBodyNode body, FrameDescriptor frameDescriptor, JSFunctionData functionData, SourceSection sourceSection, String internalFunctionName) {
        FunctionRootNode rootNode = new FunctionRootNode(body, frameDescriptor, functionData, sourceSection, internalFunctionName);
        if (JSTruffleOptions.TestCloneUninitialized) {
            return (FunctionRootNode) rootNode.cloneUninitialized();
        } else {
            return rootNode;
        }
    }

    public JSFunctionData getFunctionData() {
        return functionData;
    }

    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    protected boolean isCloneUninitializedSupported() {
        return true;
    }

    @Override
    protected JavaScriptRootNode cloneUninitialized() {
        return new FunctionRootNode((AbstractBodyNode) JavaScriptNode.cloneUninitialized(body), getFrameDescriptor(), functionData, getSourceSection(), internalFunctionName);
    }

    public boolean isInlineImmediately() {
        return functionData.isBuiltin();
    }

    @Override
    public String getName() {
        if (functionData.isBuiltin() || (functionData.getName().isEmpty() && internalFunctionName != null)) {
            assert internalFunctionName != null;
            return internalFunctionName;
        }
        return functionData.getName();
    }

    @Override
    public String toString() {
        return getName();
    }

    public JavaScriptNode getBody() {
        return body;
    }

    public JSContext getContext() {
        return functionData.getContext();
    }

    @Override
    protected Object executeAndSetRealm(VirtualFrame frame) {
        return body.execute(frame);
    }

    @Override
    protected JSRealm getRealm() {
        return functionData.getContext().getRealm();
    }

    public void setFunctionData(JSFunctionData functionData) {
        this.functionData = functionData;
    }

    @Override
    @TruffleBoundary
    public Map<String, Object> getDebugProperties() {
        Map<String, Object> map = super.getDebugProperties();
        map.put("name", "function " + getName() + "(" + getParamCount() + "/" + getFrameDescriptor().getSize() + ")");
        return map;
    }

    public int getParamCount() {
        return functionData.getLength();
    }

    @Override
    public boolean isFunction() {
        return true;
    }

    @Override
    public void initializeRoot(JSFunctionData fd) {
        fd.setRootTarget(Truffle.getRuntime().createCallTarget(this));
    }

    @Override
    public void initializeCallTarget(JSFunctionData fd, Target target, CallTarget rootTarget) {
        initializeFunctionDataCallTarget(fd, target, rootTarget, this);
    }

    private static void initializeFunctionDataCallTarget(JSFunctionData functionData, JSFunctionData.Target target, CallTarget rootTarget, FunctionRootNode functionRoot) {
        NodeFactory factory = NodeFactory.getDefaultInstance();
        if (target == JSFunctionData.Target.Call) {
            CallTarget functionCallTarget;
            if (functionData.requiresNew()) {
                functionCallTarget = Truffle.getRuntime().createCallTarget(factory.createConstructorRequiresNewRoot(functionData.getContext(), functionRoot.getSourceSection()));
            } else {
                if (functionData.needsNewTarget()) {
                    // functions that use new.target are wrapped with a delegating call target that
                    // supplies an additional implicit newTarget argument to the original function.
                    functionCallTarget = Truffle.getRuntime().createCallTarget(factory.createNewTargetCall(rootTarget));
                } else {
                    functionCallTarget = rootTarget;
                }
            }
            functionData.setCallTarget(functionCallTarget);
        } else if (target == JSFunctionData.Target.Construct) {
            CallTarget constructCallTarget;
            if (functionData.isGenerator()) {
                constructCallTarget = functionData.getContext().getGeneratorNotConstructibleCallTarget();
            } else if (functionData.isAsync()) {
                constructCallTarget = functionData.getContext().getNotConstructibleCallTarget();
            } else {
                constructCallTarget = Truffle.getRuntime().createCallTarget(factory.createConstructorRootNode(functionData, rootTarget, false));
                if (functionData.needsNewTarget()) {
                    // functions that use new.target are wrapped with a delegating call target that
                    // supplies an additional implicit newTarget argument to the original function.
                    constructCallTarget = Truffle.getRuntime().createCallTarget(factory.createNewTargetConstruct(constructCallTarget));
                }
            }
            functionData.setConstructTarget(constructCallTarget);
        } else if (target == JSFunctionData.Target.ConstructNewTarget) {
            CallTarget newTargetCallTarget;
            if (functionData.needsNewTarget()) {
                newTargetCallTarget = rootTarget;
            } else {
                newTargetCallTarget = Truffle.getRuntime().createCallTarget(factory.createDropNewTarget(rootTarget));
            }
            CallTarget constructNewTargetCallTarget = Truffle.getRuntime().createCallTarget(factory.createConstructorRootNode(functionData, newTargetCallTarget, true));
            functionData.setConstructNewTarget(constructNewTargetCallTarget);
        }
    }
}