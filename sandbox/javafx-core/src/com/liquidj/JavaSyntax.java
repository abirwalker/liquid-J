package com.liquidj;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/** Reads Java source text: syntax colour spans for the editor and the class/method around a position. */
final class JavaSyntax {

    enum SymbolKind { CLASS, METHOD }

    record Symbol(SymbolKind kind, String name) {}

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "record", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
            "var", "void", "volatile", "while", "yield", "sealed", "permits", "non", "true", "false", "null");

    // Words that can sit before "(...) {" without declaring a method.
    private static final Set<String> NOT_METHODS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "do", "else", "return", "new");

    // An unterminated comment or text block runs to the end, as javac reads it.
    private static final String COMMENT = "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)";
    private static final String STRING = "\"\"\"[\\s\\S]*?(?:\"\"\"|$)|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])+'";

    private static final Pattern TOKENS = Pattern.compile(
            "(?<COMMENT>" + COMMENT + ")"
                    + "|(?<STRING>" + STRING + ")"
                    + "|(?<ANNOTATION>@[A-Za-z_$][\\w$]*)"
                    + "|(?<NUMBER>\\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|\\d[\\d_]*(?:\\.\\d[\\d_]*)?(?:[eE][+-]?\\d+)?)[lLfFdD]?\\b)"
                    + "|(?<WORD>[A-Za-z_$][\\w$]*)");
    private static final Pattern COMMENTS_AND_LITERALS = Pattern.compile(COMMENT + "|" + STRING);

    private static final Pattern ANNOTATION_USE = Pattern.compile("@[\\w$.]+(?:\\([^)]*\\))?");
    private static final Pattern TYPE_HEADER = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern METHOD_HEADER =
            Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?$");

    private JavaSyntax() {
    }

    /** One style class per token: keyword, type, method, string, number, comment, annotation. */
    static StyleSpans<Collection<String>> highlight(String text) {
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        Matcher match = TOKENS.matcher(text);
        int end = 0;
        while (match.find()) {
            String style = styleOf(match, text);
            if (style == null) {
                continue;
            }
            spans.add(List.of(), match.start() - end);
            spans.add(List.of(style), match.end() - match.start());
            end = match.end();
        }
        spans.add(List.of(), text.length() - end);
        return spans.create();
    }

    /** Replaces comments, strings and chars with spaces, keeping every offset and newline. */
    static String maskCommentsAndLiterals(String code) {
        StringBuilder masked = new StringBuilder(code);
        Matcher match = COMMENTS_AND_LITERALS.matcher(code);
        while (match.find()) {
            for (int i = match.start(); i < match.end(); i++) {
                if (masked.charAt(i) != '\n') {
                    masked.setCharAt(i, ' ');
                }
            }
        }
        return masked.toString();
    }

    /** The classes and method that enclose the position, outermost first. */
    static List<Symbol> symbolsAt(String text, int position) {
        String code = maskCommentsAndLiterals(text);
        int limit = Math.min(position, code.length());
        Deque<Symbol> open = new ArrayDeque<>();
        Deque<Boolean> named = new ArrayDeque<>();
        int headerStart = 0;
        for (int i = 0; i < limit; i++) {
            char ch = code.charAt(i);
            if (ch == '{') {
                Symbol symbol = symbolFor(code.substring(headerStart, i));
                named.push(symbol != null);
                if (symbol != null) {
                    open.push(symbol);
                }
                headerStart = i + 1;
            } else if (ch == '}') {
                if (!named.isEmpty() && named.pop()) {
                    open.pop();
                }
                headerStart = i + 1;
            } else if (ch == ';') {
                headerStart = i + 1;
            }
        }
        List<Symbol> outermostFirst = new ArrayList<>(open);
        Collections.reverse(outermostFirst);
        return outermostFirst;
    }

    private static Symbol symbolFor(String header) {
        String clean = ANNOTATION_USE.matcher(header).replaceAll(" ").replaceAll("\\s+", " ").strip();
        Matcher type = TYPE_HEADER.matcher(clean);
        if (type.find()) {
            return new Symbol(SymbolKind.CLASS, type.group(1));
        }
        Matcher method = METHOD_HEADER.matcher(clean);
        if (method.find()) {
            String name = method.group(1);
            String before = clean.substring(0, method.start()).strip();
            if (!NOT_METHODS.contains(name) && !before.endsWith("new") && !before.endsWith(".")) {
                return new Symbol(SymbolKind.METHOD, name);
            }
        }
        return null;
    }

    private static String styleOf(Matcher match, String text) {
        if (match.group("COMMENT") != null) {
            return "comment";
        }
        if (match.group("STRING") != null) {
            return "string";
        }
        if (match.group("ANNOTATION") != null) {
            return "annotation";
        }
        if (match.group("NUMBER") != null) {
            return "number";
        }
        String word = match.group("WORD");
        if (KEYWORDS.contains(word)) {
            return "keyword";
        }
        if (Character.isUpperCase(word.charAt(0))) {
            return "type";
        }
        int next = match.end();
        while (next < text.length() && (text.charAt(next) == ' ' || text.charAt(next) == '\t')) {
            next++;
        }
        return next < text.length() && text.charAt(next) == '(' ? "method" : null;
    }
}
