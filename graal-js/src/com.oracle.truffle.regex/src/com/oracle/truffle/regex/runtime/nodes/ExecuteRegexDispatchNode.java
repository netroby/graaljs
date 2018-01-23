/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.runtime.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.regex.CompiledRegex;
import com.oracle.truffle.regex.result.RegexResult;
import com.oracle.truffle.regex.runtime.RegexResultObject;
import com.oracle.truffle.regex.util.NumberConversion;

public abstract class ExecuteRegexDispatchNode extends Node {

    public abstract RegexResultObject execute(CompiledRegex receiver, Object input, Object fromIndex);

    @Specialization(guards = "receiver == cachedReceiver", limit = "4")
    public RegexResultObject doCached(CompiledRegex receiver, Object input, Object fromIndex,
                    @Cached("create()") ExpectStringOrTruffleObjectNode expectStringOrTruffleObjectNode,
                    @Cached("create()") ExpectNumberNode expectNumberNode,
                    @SuppressWarnings("unused") @Cached("receiver") CompiledRegex cachedReceiver,
                    @Cached("create(cachedReceiver.getCallTarget())") DirectCallNode directCallNode) {
        final Object unboxedInput = expectStringOrTruffleObjectNode.execute(input);
        final Number fromIndexNumber = expectNumberNode.execute(fromIndex);
        if (fromIndexNumber instanceof Long && ((Long) fromIndexNumber) > Integer.MAX_VALUE) {
            return new RegexResultObject(RegexResult.NO_MATCH);
        }
        return new RegexResultObject((RegexResult) directCallNode.call(new Object[]{receiver, unboxedInput, NumberConversion.intValue(fromIndexNumber)}));
    }

    @Specialization(replaces = "doCached")
    public RegexResultObject doUnCached(CompiledRegex receiver, Object input, Object fromIndex,
                    @Cached("create()") ExpectStringOrTruffleObjectNode expectStringOrTruffleObjectNode,
                    @Cached("create()") ExpectNumberNode expectNumberNode,
                    @Cached("create()") IndirectCallNode indirectCallNode) {
        final Object unboxedInput = expectStringOrTruffleObjectNode.execute(input);
        final Number fromIndexNumber = expectNumberNode.execute(fromIndex);
        if (fromIndexNumber instanceof Long && ((Long) fromIndexNumber) > Integer.MAX_VALUE) {
            return new RegexResultObject(RegexResult.NO_MATCH);
        }
        return new RegexResultObject((RegexResult) indirectCallNode.call(receiver.getCallTarget(), new Object[]{receiver, unboxedInput, NumberConversion.intValue(fromIndexNumber)}));
    }

    public static ExecuteRegexDispatchNode create() {
        return ExecuteRegexDispatchNodeGen.create();
    }
}