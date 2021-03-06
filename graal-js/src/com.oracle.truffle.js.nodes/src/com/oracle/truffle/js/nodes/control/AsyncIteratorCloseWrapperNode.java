/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.control;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.access.GetMethodNode;
import com.oracle.truffle.js.nodes.access.JSReadFrameSlotNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.objects.Completion;
import com.oracle.truffle.js.runtime.objects.IteratorRecord;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Undefined;

/**
 * ES8 5.2 AsyncIteratorClose(iterator, completion).
 */
public class AsyncIteratorCloseWrapperNode extends AwaitNode {

    private final JSContext context;
    @Child private JavaScriptNode loopNode;
    @Child private GetMethodNode getReturnNode;
    @Child private JSFunctionCallNode returnMethodCallNode;
    @Child private JavaScriptNode iteratorNode;
    @Child private JavaScriptNode doneNode;
    private final BranchProfile errorBranch = BranchProfile.create();
    private final BranchProfile throwBranch = BranchProfile.create();
    private final BranchProfile exitBranch = BranchProfile.create();
    private final BranchProfile notDoneBranch = BranchProfile.create();

    protected AsyncIteratorCloseWrapperNode(JSContext context, JavaScriptNode loopNode, JavaScriptNode iteratorNode, JSReadFrameSlotNode asyncContextNode, JSReadFrameSlotNode asyncResultNode,
                    JavaScriptNode doneNode) {
        super(context, null, asyncContextNode, asyncResultNode);
        this.context = context;
        this.loopNode = loopNode;
        this.iteratorNode = iteratorNode;
        this.doneNode = doneNode;

        this.getReturnNode = GetMethodNode.create(context, null, "return");
    }

    public static JavaScriptNode create(JSContext context, JavaScriptNode loopNode, JavaScriptNode iterator, JSReadFrameSlotNode asyncContextNode,
                    JSReadFrameSlotNode asyncResultNode, JavaScriptNode doneNode) {
        return new AsyncIteratorCloseWrapperNode(context, loopNode, iterator, asyncContextNode, asyncResultNode, doneNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object result;
        Object innerResult;
        Completion completion;
        await: {
            try {
                result = loopNode.execute(frame);
            } catch (YieldException e) {
                throw e;
            } catch (ControlFlowException e) {
                exitBranch.enter();
                IteratorRecord iteratorRecord = getIteratorRecord(frame);
                DynamicObject iterator = iteratorRecord.getIterator();
                Object returnMethod = getReturnNode.executeWithTarget(frame, iterator);
                if (returnMethod != Undefined.instance) {
                    innerResult = returnMethodCallNode().executeCall(JSArguments.createZeroArg(iterator, returnMethod));
                    completion = Completion.forReturn(e);
                    break await;
                } else {
                    throw e;
                }
            } catch (Throwable e) {
                if (TryCatchNode.shouldCatch(e)) {
                    throwBranch.enter();
                    IteratorRecord iteratorRecord = getIteratorRecord(frame);
                    DynamicObject iterator = iteratorRecord.getIterator();
                    Object returnMethod = getReturnNode.executeWithTarget(frame, iterator);
                    if (returnMethod != Undefined.instance) {
                        try {
                            innerResult = returnMethodCallNode().executeCall(JSArguments.createZeroArg(iterator, returnMethod));
                        } catch (Exception ex) {
                            // re-throw outer exception
                            throw e;
                        }
                        completion = Completion.forThrow(e);
                        break await;
                    } else {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
            if (StatementNode.executeConditionAsBoolean(frame, doneNode)) {
                return result;
            } else {
                notDoneBranch.enter();
                IteratorRecord iteratorRecord = getIteratorRecord(frame);
                DynamicObject iterator = iteratorRecord.getIterator();
                Object returnMethod = getReturnNode.executeWithTarget(frame, iterator);
                if (returnMethod != Undefined.instance) {
                    innerResult = returnMethodCallNode().executeCall(JSArguments.createZeroArg(iterator, returnMethod));
                    completion = Completion.forNormal(result);
                    break await;
                } else {
                    return result;
                }
            }
        }
        setState(frame, completion);
        return suspendAwait(frame, innerResult);
    }

    @Override
    public Object resume(VirtualFrame frame) {
        Object state = getState(frame);
        if (state == Undefined.instance) {
            return execute(frame);
        } else {
            resetState(frame);
            Completion completion = (Completion) state;
            if (completion.isThrow()) {
                throw TryFinallyNode.rethrow((Throwable) completion.getValue());
            }
            Object innerResult = resumeAwait(frame);
            if (!JSObject.isJSObject(innerResult)) {
                errorBranch.enter();
                throw Errors.createTypeErrorIterResultNotAnObject(innerResult, this);
            }
            if (completion.isAbrupt()) {
                throw TryFinallyNode.rethrow((Throwable) completion.getValue());
            }
            return completion.getValue();
        }
    }

    private IteratorRecord getIteratorRecord(VirtualFrame frame) {
        return (IteratorRecord) iteratorNode.execute(frame);
    }

    private JSFunctionCallNode returnMethodCallNode() {
        if (returnMethodCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            returnMethodCallNode = insert(JSFunctionCallNode.createCall());
        }
        return returnMethodCallNode;
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return new AsyncIteratorCloseWrapperNode(context, cloneUninitialized(loopNode), cloneUninitialized(iteratorNode), cloneUninitialized(readAsyncContextNode),
                        cloneUninitialized(readAsyncResultNode), cloneUninitialized(doneNode));
    }
}
