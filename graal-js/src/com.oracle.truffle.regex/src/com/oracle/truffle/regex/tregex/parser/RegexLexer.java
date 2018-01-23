/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.tregex.parser;

import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.RegexOptions;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.util.CompilationFinalBitSet;
import com.oracle.truffle.regex.util.Constants;

import java.util.EnumSet;

public final class RegexLexer {

    private static final CompilationFinalBitSet PREDEFINED_CHAR_CLASSES = CompilationFinalBitSet.valueOf('s', 'S', 'd', 'D', 'w', 'W');
    private static final CompilationFinalBitSet SYNTAX_CHARS = CompilationFinalBitSet.valueOf(
                    '^', '$', '/', '\\', '.', '*', '+', '?', '(', ')', '[', ']', '{', '}', '|');

    private final String pattern;
    private final RegexFlags flags;
    private final RegexOptions options;
    private Token lastToken;
    private int index = 0;
    private int nGroups = 1;

    public RegexLexer(RegexSource source) {
        this.pattern = source.getPattern();
        this.flags = source.getFlags();
        this.options = source.getOptions();
    }

    public boolean hasNext() {
        return !atEnd();
    }

    public Token next() throws RegexSyntaxException {
        Token t = getNext();
        lastToken = t;
        return t;
    }

    /* input string access */

    private char curChar() {
        return pattern.charAt(index);
    }

    private char consumeChar() {
        final char c = pattern.charAt(index);
        advance();
        return c;
    }

    private void advance() {
        advance(1);
    }

    private void retreat() {
        advance(-1);
    }

    private void advance(int len) {
        index += len;
    }

    private boolean lookahead(String match) {
        if (pattern.length() - index < match.length()) {
            return false;
        }
        return pattern.regionMatches(index, match, 0, match.length());
    }

    private boolean lookaheadFindChar(char c) {
        return pattern.indexOf(c, index) >= 0;
    }

    private boolean consumingLookahead(String match) {
        final boolean matches = lookahead(match);
        if (matches) {
            advance(match.length());
        }
        return matches;
    }

    private boolean atEnd() {
        return index >= pattern.length();
    }

    private Token charClass(CodePointSet codePointSet) {
        return charClass(codePointSet, false);
    }

    private Token charClass(CodePointSet codePointSet, boolean invert) {
        CodePointSet processedSet = codePointSet;
        processedSet = flags.isIgnoreCase() ? CaseFoldTable.applyCaseFold(processedSet, flags.isUnicode()) : processedSet;
        processedSet = invert ? processedSet.createInverse() : processedSet;
        return Token.createCharClass(processedSet);
    }

    /* lexer */

    private Token getNext() throws RegexSyntaxException {
        final char c = consumeChar();
        switch (c) {
            case '.':
                return charClass(flags.isDotAll() ? Constants.DOT_ALL : Constants.DOT);
            case '^':
                return Token.create(Token.Kind.caret);
            case '$':
                return Token.create(Token.Kind.dollar);
            case '{':
            case '*':
            case '+':
            case '?':
                return parseQuantifier(c);
            case '}':
                if (flags.isUnicode()) {
                    // In ECMAScript regular expressions, syntax characters such as '}' and ']'
                    // cannot be used as atomic patterns. However, Annex B relaxes this condition
                    // and allows the use of unmatched '}' and ']', which then match themselves.
                    // Neverthelesss, in Unicode mode, we should still be strict.
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACE);
                }
                return charClass(CodePointSet.create(c));
            case '|':
                return Token.create(Token.Kind.alternation);
            case '(':
                return parseGroupBegin();
            case ')':
                return Token.create(Token.Kind.groupEnd);
            case '[':
                return parseCharClass();
            case ']':
                if (flags.isUnicode()) {
                    throw syntaxError(ErrorMessages.UNMATCHED_RIGHT_BRACKET);
                }
                return charClass(CodePointSet.create(c));
            case '\\':
                return parseEscape();
            default:
                if (flags.isUnicode() && Character.isHighSurrogate(c)) {
                    return charClass(CodePointSet.create(finishSurrogatePair(c)));
                }
                return charClass(CodePointSet.create(c));
        }
    }

    private Token parseEscape() throws RegexSyntaxException {
        if (atEnd()) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
        }
        final char c = consumeChar();
        if ('1' <= c && c <= '9') {
            final int restoreIndex = index;
            final int backRefNumber = parseDecimal(c - '0');
            if (backRefNumber < nGroups || lookaheadFindChar('(')) {
                return Token.createBackReference(backRefNumber);
            }
            index = restoreIndex;
        }
        switch (c) {
            case 'b':
                return Token.create(Token.Kind.wordBoundary);
            case 'B':
                return Token.create(Token.Kind.nonWordBoundary);
            default:
                // Here we differentiate the case when parsing one of the six basic pre-defined
                // character classes (\w, \W, \d, \D, \s, \S) and Unicode character property
                // escapes. Both result in sets of characters, but in the former case, we can skip
                // the case-folding step in the `charClass` method and call `Token::createCharClass`
                // directly.
                if (isPredefCharClass(c)) {
                    return Token.createCharClass(parsePredefCharClass(c));
                } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
                    return charClass(parseUnicodeCharacterProperty(c == 'P'));
                } else {
                    return charClass(CodePointSet.create(parseEscapeChar(c, false)));
                }
        }
    }

    private Token parseGroupBegin() {
        if (consumingLookahead("?=")) {
            return Token.create(Token.Kind.lookAheadAssertionBegin);
        } else if (consumingLookahead("?!")) {
            return Token.create(Token.Kind.negativeLookAheadAssertionBegin);
        } else if (consumingLookahead("?<=")) {
            return Token.create(Token.Kind.lookBehindAssertionBegin);
        } else if (consumingLookahead("?:")) {
            return Token.create(Token.Kind.nonCaptureGroupBegin);
        } else {
            nGroups++;
            return Token.create(Token.Kind.captureGroupBegin);
        }
    }

    private static final EnumSet<Token.Kind> QUANTIFIER_PREV = EnumSet.of(Token.Kind.charClass, Token.Kind.groupEnd, Token.Kind.backReference);

    private Token parseQuantifier(char c) throws RegexSyntaxException {
        int min;
        int max = -1;
        boolean greedy;
        if (c == '{') {
            final int resetIndex = index;
            min = parseDecimal();
            if (min < 0) {
                return countedRepetitionSyntaxError(resetIndex);
            }
            if (consumingLookahead(",}")) {
                greedy = !consumingLookahead("?");
            } else if (consumingLookahead("}")) {
                max = min;
                greedy = !consumingLookahead("?");
            } else {
                if (!consumingLookahead(",") || (max = parseDecimal()) < 0 || !consumingLookahead("}")) {
                    return countedRepetitionSyntaxError(resetIndex);
                }
                greedy = !consumingLookahead("?");
            }
        } else {
            greedy = !consumingLookahead("?");
            min = c == '+' ? 1 : 0;
            if (c == '?') {
                max = 1;
            }
        }
        if (lastToken == null) {
            throw syntaxError(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        if (lastToken.kind == Token.Kind.quantifier) {
            throw syntaxError(ErrorMessages.QUANTIFIER_ON_QUANTIFIER);
        }
        if (!QUANTIFIER_PREV.contains(lastToken.kind)) {
            throw syntaxError(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        return Token.createQuantifier(min, max, greedy);
    }

    private Token countedRepetitionSyntaxError(int resetIndex) throws RegexSyntaxException {
        if (flags.isUnicode()) {
            throw syntaxError(ErrorMessages.INCOMPLETE_QUANTIFIER);
        }
        index = resetIndex;
        return charClass(CodePointSet.create('{'));
    }

    private Token parseCharClass() throws RegexSyntaxException {
        final boolean invert = consumingLookahead("^");
        CodePointSet curCharClass = CodePointSet.createEmpty();
        while (!atEnd()) {
            final char c = consumeChar();
            if (c == ']') {
                return charClass(curCharClass, invert);
            }
            curCharClass = parseCharClassAtom(c, curCharClass);
        }
        throw syntaxError(ErrorMessages.UNMATCHED_LEFT_BRACKET);
    }

    private CodePointSet parseCharClassAtom(char c, CodePointSet curCharClass) throws RegexSyntaxException {
        int curChar;
        if (c == '\\') {
            if (atEnd()) {
                throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
            }
            if (isEscapeCharClass(curChar())) {
                final char cc = consumeChar();
                curCharClass.addSet(parseEscapeCharClass(cc));
                // NOTE: Tolerating character classes to the left of the hyphen and then treating
                // the hyphen literally is legacy and only permitted by Annex B.
                if (flags.isUnicode() && lookahead("-") && !lookahead("-]")) {
                    throw syntaxError(ErrorMessages.INVALID_CHARACTER_CLASS);
                }
                return curCharClass;
            }
            curChar = parseEscapeChar(consumeChar(), true);
        } else if (flags.isUnicode() && Character.isHighSurrogate(c)) {
            curChar = finishSurrogatePair(c);
        } else {
            curChar = c;
        }
        if (consumingLookahead("-")) {
            if (atEnd() || lookahead("]")) {
                curCharClass.addRange(new CodePointRange(curChar));
                curCharClass.addRange(new CodePointRange((int) '-'));
                return curCharClass;
            }
            int nextChar;
            if (consumingLookahead("\\")) {
                if (atEnd()) {
                    throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
                }
                if (isEscapeCharClass(curChar())) {
                    if (flags.isUnicode()) {
                        throw syntaxError(ErrorMessages.INVALID_CHARACTER_CLASS);
                    }
                    curCharClass.addRange(new CodePointRange(curChar));
                    curCharClass.addRange(new CodePointRange((int) '-'));
                    curCharClass.addSet(parseEscapeCharClass(consumeChar()));
                    return curCharClass;
                }
                nextChar = parseEscapeChar(consumeChar(), true);
            } else {
                char nextCodeUnit = consumeChar();
                if (flags.isUnicode() && Character.isHighSurrogate(nextCodeUnit)) {
                    nextChar = finishSurrogatePair(nextCodeUnit);
                } else {
                    nextChar = nextCodeUnit;
                }
            }
            if (nextChar < curChar) {
                throw syntaxError(ErrorMessages.CHAR_CLASS_RANGE_OUT_OF_ORDER);
            } else {
                curCharClass.addRange(new CodePointRange(curChar, nextChar));
            }
        } else {
            curCharClass.addRange(new CodePointRange(curChar));
        }
        return curCharClass;
    }

    private CodePointSet parseEscapeCharClass(char c) throws RegexSyntaxException {
        if (isPredefCharClass(c)) {
            return parsePredefCharClass(c);
        } else if (flags.isUnicode() && (c == 'p' || c == 'P')) {
            return parseUnicodeCharacterProperty(c == 'P');
        } else {
            throw new IllegalStateException();
        }
    }

    // Note that the CodePointSet returned by this function has already been
    // case-folded and negated.
    private CodePointSet parsePredefCharClass(char c) {
        switch (c) {
            case 's':
                if (options.isU180EWhitespace()) {
                    return Constants.LEGACY_WHITE_SPACE;
                } else {
                    return Constants.WHITE_SPACE;
                }
            case 'S':
                if (options.isU180EWhitespace()) {
                    return Constants.LEGACY_NON_WHITE_SPACE;
                } else {
                    return Constants.NON_WHITE_SPACE;
                }
            case 'd':
                return Constants.DIGITS;
            case 'D':
                return Constants.NON_DIGITS;
            case 'w':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.WORD_CHARS;
                }
            case 'W':
                if (flags.isUnicode() && flags.isIgnoreCase()) {
                    return Constants.NON_WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.NON_WORD_CHARS;
                }
            default:
                throw new IllegalStateException();
        }
    }

    private CodePointSet parseUnicodeCharacterProperty(boolean invert) throws RegexSyntaxException {
        if (!consumingLookahead("{")) {
            throw syntaxError(ErrorMessages.INVALID_UNICODE_PROPERTY);
        }
        StringBuilder propSpecBuilder = new StringBuilder();
        while (!atEnd() && curChar() != '}') {
            propSpecBuilder.append(consumeChar());
        }
        if (!consumingLookahead("}")) {
            throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_UNICODE_PROPERTY);
        }
        try {
            CodePointSet propertySet = UnicodeCharacterProperties.getProperty(propSpecBuilder.toString());
            return invert ? propertySet.createInverse() : propertySet;
        } catch (IllegalArgumentException e) {
            throw syntaxError(e.getMessage());
        }
    }

    private int parseEscapeChar(char c, boolean inCharClass) throws RegexSyntaxException {
        if (inCharClass && c == 'b') {
            return '\b';
        }
        switch (c) {
            case '0':
                if (flags.isUnicode() && isDecimal(curChar())) {
                    throw syntaxError(ErrorMessages.INVALID_ESCAPE);
                }
                // NOTE: Octal escapes are considered legacy by the ECMAScript spec and are
                // relegated to Annex B. Do we still want to support them?
                if (!flags.isUnicode() && !atEnd() && isOctal(curChar())) {
                    return parseOctal(0);
                }
                return '\0';
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'v':
                return '\u000B';
            case 'f':
                return '\f';
            case 'r':
                return '\r';
            case 'c':
                if (atEnd()) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                final char controlLetter = curChar();
                if (!flags.isUnicode() && (isDecimal(controlLetter) || controlLetter == '_') && inCharClass) {
                    advance();
                    return controlLetter % 32;
                }
                if (!('a' <= controlLetter && controlLetter <= 'z' || 'A' <= controlLetter && controlLetter <= 'Z')) {
                    retreat();
                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
                }
                advance();
                return Character.toUpperCase(controlLetter) - ('A' - 1);
            case 'u':
                if (flags.isUnicode() && consumingLookahead("{")) {
                    final int value = parseHex(1, Integer.MAX_VALUE, 0x10ffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                    if (!consumingLookahead("}")) {
                        throw syntaxError(ErrorMessages.INVALID_UNICODE_ESCAPE);
                    }
                    return value;
                } else {
                    final int value = parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                    if (flags.isUnicode() && Character.isHighSurrogate((char) value)) {
                        final int resetIndex = index;
                        if (consumingLookahead("\\u") && !lookahead("{")) {
                            final char lead = (char) value;
                            final char trail = (char) parseHex(4, 4, 0xffff, ErrorMessages.INVALID_UNICODE_ESCAPE);
                            if (Character.isLowSurrogate(trail)) {
                                return Character.toCodePoint(lead, trail);
                            } else {
                                index = resetIndex;
                            }
                        } else {
                            index = resetIndex;
                        }
                    }
                    return value < 0 ? c : value;
                }
            case 'x':
                final int value = parseHex(2, 2, 0xff, ErrorMessages.INVALID_ESCAPE);
                return value < 0 ? c : value;
            case '-':
                if (!inCharClass) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
            default:
                // NOTE: Octal escapes are considered legacy by the ECMAScript spec and are
                // relegated to Annex B. Do we still want to support them?
                if (!flags.isUnicode() && isOctal(c)) {
                    return parseOctal(c - '0');
                }
                if (!SYNTAX_CHARS.get(c)) {
                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
        }
    }

    private int finishSurrogatePair(char c) {
        assert flags.isUnicode() && Character.isHighSurrogate(c);
        if (!atEnd() && Character.isLowSurrogate(curChar())) {
            final char lead = c;
            final char trail = consumeChar();
            return Character.toCodePoint(lead, trail);
        } else {
            return c;
        }
    }

    private char escapeCharSyntaxError(char c, String msg) throws RegexSyntaxException {
        // NOTE: Throwing SyntaxErrors for invalid escapes only in Unicode mode is legacy behavior
        // (relegated to Annex B).
        if (flags.isUnicode()) {
            throw syntaxError(msg);
        }
        return c;
    }

    private int parseDecimal() {
        if (atEnd() || !isDecimal(curChar())) {
            return -1;
        }
        return parseDecimal(0);
    }

    private int parseDecimal(int firstDigit) {
        int ret = firstDigit;
        while (!atEnd() && isDecimal(curChar())) {
            ret *= 10;
            ret += consumeChar() - '0';
        }
        return ret;
    }

    private int parseOctal(int firstDigit) {
        int ret = firstDigit;
        for (int i = 0; !atEnd() && isOctal(curChar()) && i < 2; i++) {
            if (ret * 8 > 255) {
                return ret;
            }
            ret *= 8;
            ret += consumeChar() - '0';
        }
        return ret;
    }

    private int parseHex(int minDigits, int maxDigits, int maxValue, String errorMsg) throws RegexSyntaxException {
        int ret = 0;
        int initialIndex = index;
        for (int i = 0; i < maxDigits; i++) {
            if (atEnd() || !isHex(curChar())) {
                if (i < minDigits) {
                    if (flags.isUnicode()) {
                        throw syntaxError(errorMsg);
                    } else {
                        // NOTE: Throwing SyntaxErrors for invalid escapes only in Unicode mode is
                        // legacy behavior (relegated to Annex B).
                        index = initialIndex;
                        return -1;
                    }
                } else {
                    break;
                }
            }
            final char c = consumeChar();
            ret *= 16;
            if (c >= 'a') {
                ret += c - ('a' - 10);
            } else if (c >= 'A') {
                ret += c - ('A' - 10);
            } else {
                ret += c - '0';
            }
            if (ret > maxValue) {
                throw syntaxError(errorMsg);
            }
        }
        return ret;
    }

    private static boolean isDecimal(char c) {
        return '0' <= c && c <= '9';
    }

    private static boolean isOctal(char c) {
        return '0' <= c && c <= '7';
    }

    private static boolean isHex(char c) {
        return '0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F';
    }

    private static boolean isPredefCharClass(char c) {
        return PREDEFINED_CHAR_CLASSES.get(c);
    }

    private boolean isEscapeCharClass(char c) {
        return isPredefCharClass(c) || (flags.isUnicode() && (c == 'p' || c == 'P'));
    }

    private RegexSyntaxException syntaxError(String msg) {
        return new RegexSyntaxException(pattern, flags, msg);
    }
}