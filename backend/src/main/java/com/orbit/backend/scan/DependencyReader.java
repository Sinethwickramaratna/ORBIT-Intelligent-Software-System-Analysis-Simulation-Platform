package com.orbit.backend.scan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the dependency names out of a dependency manifest, with the line and column of every name. This is metadata
 * parsing, not a text search: a name only counts where the file format declares a dependency (a Maven
 * {@code <dependency>}, {@code <parent>} or {@code <plugin>} {@code artifactId} - never the project's own artifactId or an
 * exclusion -, a Gradle dependency string, a key under {@code dependencies} in package.json, a requirement line, ...).
 * Comments are ignored. Lines and columns are 1-based, columns count characters.
 *
 * <p>Supported manifests (package manager in brackets): pom.xml, build.gradle, build.gradle.kts (maven - Maven and
 * Gradle both use Maven coordinates), package.json (npm), requirements*.txt, pyproject.toml, Pipfile (pip),
 * pubspec.yaml (pub), *.csproj (nuget).
 */
public final class DependencyReader {

    /** A dependency as written in the file (e.g. {@code spring-boot-starter-web}) and where its name starts. */
    public record Dependency(String name, int line, int column) {
    }

    /** The dependencies of one manifest and the package manager they belong to. */
    public record Reading(String packageManager, List<Dependency> dependencies) {
    }

    public static final String MAVEN = "maven";
    public static final String NPM = "npm";
    public static final String PIP = "pip";
    public static final String PUB = "pub";
    public static final String NUGET = "nuget";

    private DependencyReader() {
    }

    /** The package manager of a manifest file name, or empty when this file is not a supported manifest. */
    public static Optional<String> packageManagerOf(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts")) {
            return Optional.of(MAVEN);
        }
        if (n.equals("package.json")) {
            return Optional.of(NPM);
        }
        if ((n.startsWith("requirements") && n.endsWith(".txt")) || n.equals("pyproject.toml") || n.equals("pipfile")) {
            return Optional.of(PIP);
        }
        if (n.equals("pubspec.yaml")) {
            return Optional.of(PUB);
        }
        if (n.endsWith(".csproj")) {
            return Optional.of(NUGET);
        }
        return Optional.empty();
    }

    /** The form in which names are compared with the catalog: lower case; Python names per PEP 503 (_ . - are one). */
    public static String normalize(String packageManager, String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        return PIP.equals(packageManager) ? n.replaceAll("[-_.]+", "-") : n;
    }

    /** Reads {@code content} of the manifest called {@code fileName}; empty when the file type is not supported. */
    public static Optional<Reading> read(String fileName, String content) {
        Optional<String> pm = packageManagerOf(fileName);
        if (pm.isEmpty()) {
            return Optional.empty();
        }
        String n = fileName.toLowerCase(Locale.ROOT);
        List<Dependency> deps;
        if (n.equals("pom.xml")) {
            deps = pom(content);
        } else if (n.startsWith("build.gradle")) {
            deps = gradle(content);
        } else if (n.equals("package.json")) {
            deps = packageJson(content);
        } else if (n.startsWith("requirements")) {
            deps = requirements(content);
        } else if (n.equals("pyproject.toml") || n.equals("pipfile")) {
            deps = toml(content, n.equals("pipfile"));
        } else if (n.equals("pubspec.yaml")) {
            deps = pubspec(content);
        } else {
            deps = csproj(content);
        }
        return Optional.of(new Reading(pm.get(), List.copyOf(deps)));
    }

    // ------------------------------------------------------------------------------------------ positions

    /** Offset to line / column. */
    private static final class Lines {
        private final int[] starts;

        Lines(String s) {
            List<Integer> list = new ArrayList<>();
            list.add(0);
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    list.add(i + 1);
                }
            }
            starts = list.stream().mapToInt(Integer::intValue).toArray();
        }

        Dependency at(String name, int offset) {
            int idx = Arrays.binarySearch(starts, offset);
            int line = idx >= 0 ? idx : -idx - 2;
            return new Dependency(name, line + 1, offset - starts[line] + 1);
        }
    }

    /** Replaces every character of the matched ranges by a space (newlines stay), so offsets do not move. */
    private static String blank(String s, Pattern p) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder(s);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (sb.charAt(i) != '\n' && sb.charAt(i) != '\r') {
                    sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern XML_TAG = Pattern.compile("<(/?)([A-Za-z_][\\w.:-]*)([^<>]*?)(/?)>");

    // ------------------------------------------------------------------------------------------ Maven

    private static final Set<String> POM_PARENTS = Set.of("dependency", "parent", "plugin");

    static List<Dependency> pom(String raw) {
        String s = blank(raw, XML_COMMENT);
        Lines lines = new Lines(raw);
        List<Dependency> out = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        Matcher m = XML_TAG.matcher(s);
        while (m.find()) {
            boolean closing = !m.group(1).isEmpty();
            boolean selfClosing = !m.group(4).isEmpty();
            String name = m.group(2);
            if (closing) {
                while (!stack.isEmpty() && !stack.peek().equals(name)) {
                    stack.pop();
                }
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                continue;
            }
            if (name.equals("artifactId") && !selfClosing && !stack.isEmpty() && POM_PARENTS.contains(stack.peek())
                    && !stack.contains("exclusions") && !stack.contains("exclusion")) {
                int textStart = m.end();
                int textEnd = s.indexOf('<', textStart);
                if (textEnd > textStart) {
                    String text = s.substring(textStart, textEnd);
                    String value = text.trim();
                    if (!value.isEmpty() && !value.contains("${")) {
                        out.add(lines.at(value, textStart + text.indexOf(value)));
                    }
                }
            }
            if (!selfClosing) {
                stack.push(name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ Gradle

    private static final Pattern GRADLE_STRING = Pattern.compile("([\"'])([^\"'\\r\\n]*?)\\1");
    private static final Pattern GRADLE_PLUGIN_ID = Pattern.compile("\\bid\\s*\\(?\\s*$");
    private static final Pattern GRADLE_NAMED = Pattern.compile("\\b(name|plugin)\\s*:\\s*$");

    /** Removes // and block comments, quote aware (a // inside a string, such as a URL, is not a comment). */
    static String stripCStyleComments(String s) {
        StringBuilder sb = new StringBuilder(s);
        char quote = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote || c == '\n') {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < sb.length() && sb.charAt(i + 1) == '/') {
                while (i < sb.length() && sb.charAt(i) != '\n') {
                    sb.setCharAt(i++, ' ');
                }
                i--;
            } else if (c == '/' && i + 1 < sb.length() && sb.charAt(i + 1) == '*') {
                int end = sb.indexOf("*/", i + 2);
                int stop = end < 0 ? sb.length() : end + 2;
                for (int k = i; k < stop; k++) {
                    if (sb.charAt(k) != '\n' && sb.charAt(k) != '\r') {
                        sb.setCharAt(k, ' ');
                    }
                }
                i = stop - 1;
            }
        }
        return sb.toString();
    }

    static List<Dependency> gradle(String raw) {
        String s = stripCStyleComments(raw);
        Lines lines = new Lines(raw);
        List<Dependency> out = new ArrayList<>();
        int lineStart = 0;
        while (lineStart <= s.length()) {
            int nl = s.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? s.length() : nl;
            String line = s.substring(lineStart, lineEnd);
            Matcher m = GRADLE_STRING.matcher(line);
            while (m.find()) {
                String value = m.group(2);
                int valueOffset = lineStart + m.start(2);
                String before = line.substring(0, m.start());
                if (value.contains("${") || value.startsWith("http") || value.contains("//")) {
                    continue;
                }
                if (value.contains(":")) {
                    String[] parts = value.split(":", -1);
                    if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank() && !parts[1].contains(" ")) {
                        out.add(lines.at(parts[1], valueOffset + parts[0].length() + 1));
                    }
                } else if (!value.isBlank() && (GRADLE_PLUGIN_ID.matcher(before).find() || GRADLE_NAMED.matcher(before).find())) {
                    out.add(lines.at(value, valueOffset));
                }
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ npm

    private static final Set<String> NPM_SECTIONS = Set.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies");

    /**
     * A small JSON walker (no library, so line and column are exact): at depth 1 it looks for the dependency sections,
     * and every key at depth 2 inside one of them is a dependency. Values are skipped, whatever they contain.
     */
    static List<Dependency> packageJson(String s) {
        List<Dependency> out = new ArrayList<>();
        Lines lines = new Lines(s);
        int depth = 0;
        String sectionKey = null;     // the last key seen at depth 1
        boolean inSection = false;    // inside the object value of a dependency section
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '"') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && s.charAt(j) != '"') {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    sb.append(s.charAt(j));
                    j++;
                }
                boolean isKey = false;
                int k = j + 1;
                while (k < n && Character.isWhitespace(s.charAt(k))) {
                    k++;
                }
                if (k < n && s.charAt(k) == ':') {
                    isKey = true;
                }
                if (isKey && depth == 1) {
                    sectionKey = sb.toString();
                } else if (isKey && depth == 2 && inSection) {
                    out.add(lines.at(sb.toString(), i + 1));
                }
                i = j;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth == 2 && c == '{' && sectionKey != null && NPM_SECTIONS.contains(sectionKey)) {
                    inSection = true;
                }
            } else if (c == '}' || c == ']') {
                if (depth == 2) {
                    inSection = false;
                }
                depth--;
                if (depth <= 0) {
                    break;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ pip

    private static final Pattern REQUIREMENT_NAME = Pattern.compile("^(\\s*)([A-Za-z0-9][A-Za-z0-9._-]*)(?=$|[\\s\\[=<>~!;@,(])");

    static List<Dependency> requirements(String raw) {
        List<Dependency> out = new ArrayList<>();
        String[] all = raw.split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            String line = all[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher m = REQUIREMENT_NAME.matcher(line);
            if (m.find()) {
                out.add(new Dependency(m.group(2), i + 1, m.end(1) + 1));
            }
        }
        return out;
    }

    private static final Pattern TOML_SECTION = Pattern.compile("^\\s*\\[+\\s*([^\\]]+?)\\s*\\]+\\s*$");
    private static final Pattern TOML_KEY = Pattern.compile("^(\\s*)([A-Za-z0-9][A-Za-z0-9._-]*)\\s*=");
    private static final Pattern TOML_STRING = Pattern.compile("([\"'])([^\"'\\r\\n]*?)\\1");

    /** pyproject.toml (PEP 621 lists and Poetry tables) and Pipfile (package tables). */
    static List<Dependency> toml(String raw, boolean pipfile) {
        List<Dependency> out = new ArrayList<>();
        String section = "";
        boolean inList = false;   // inside a multi-line dependency array
        int depth = 0;
        String[] all = raw.split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            String line = all[i];
            int hash = indexOfOutsideString(line, '#');
            String code = hash < 0 ? line : line.substring(0, hash);
            if (code.isBlank()) {
                continue;
            }
            if (!inList) {
                Matcher sec = TOML_SECTION.matcher(code);
                if (sec.matches()) {
                    section = sec.group(1).toLowerCase(Locale.ROOT);
                    continue;
                }
            }
            boolean pepTable = section.equals("project.optional-dependencies") || section.equals("dependency-groups");
            boolean tableOfPackages = pipfile ? (section.equals("packages") || section.equals("dev-packages"))
                    : (section.equals("tool.poetry.dependencies") || section.equals("tool.poetry.dev-dependencies")
                            || (section.startsWith("tool.poetry.group.") && section.endsWith(".dependencies")));
            if (tableOfPackages) {
                Matcher k = TOML_KEY.matcher(code);
                if (k.find() && !k.group(2).equalsIgnoreCase("python")) {
                    out.add(new Dependency(k.group(2), i + 1, k.end(1) + 1));
                }
                continue;
            }
            boolean pepList = !pipfile && (pepTable || section.equals("project"));
            if (!pepList) {
                continue;
            }
            int from = 0;
            if (!inList) {
                Matcher k = TOML_KEY.matcher(code);
                if (!k.find()) {
                    continue;
                }
                String key = k.group(2);
                boolean wanted = pepTable || key.equals("dependencies");
                int bracket = code.indexOf('[', k.end());
                if (!wanted || bracket < 0) {
                    continue;
                }
                inList = true;
                depth = 0;
                from = bracket;
            }
            Matcher str = TOML_STRING.matcher(code);
            str.region(from, code.length());
            while (str.find()) {
                Matcher name = REQUIREMENT_NAME.matcher(str.group(2));
                if (name.find()) {
                    out.add(new Dependency(name.group(2), i + 1, str.start(2) + name.end(1) + 1));
                }
            }
            for (int c = from; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '[') {
                    depth++;
                } else if (ch == ']') {
                    depth--;
                }
            }
            if (depth <= 0) {
                inList = false;
            }
        }
        return out;
    }

    private static int indexOfOutsideString(String line, char target) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == target) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------ pub

    private static final Pattern YAML_SECTION = Pattern.compile("^(dependencies|dev_dependencies|dependency_overrides)\\s*:\\s*(#.*)?$");
    private static final Pattern YAML_KEY = Pattern.compile("^(\\s+)([A-Za-z_][A-Za-z0-9_.-]*)\\s*:");

    static List<Dependency> pubspec(String raw) {
        List<Dependency> out = new ArrayList<>();
        boolean in = false;
        int indent = -1;
        String[] all = raw.split("\n", -1);
        for (int i = 0; i < all.length; i++) {
            String line = all[i].replace("\r", "");
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                in = YAML_SECTION.matcher(line).matches();
                indent = -1;
                continue;
            }
            if (!in) {
                continue;
            }
            Matcher k = YAML_KEY.matcher(line);
            if (k.find()) {
                int ind = k.group(1).length();
                if (indent < 0) {
                    indent = ind;
                }
                if (ind == indent) {          // deeper keys (sdk:, path:, version: ...) are options, not dependencies
                    out.add(new Dependency(k.group(2), i + 1, ind + 1));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ NuGet

    private static final Pattern CSPROJ_SDK = Pattern.compile("<Project\\b[^>]*?\\bSdk\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern CSPROJ_PACKAGE = Pattern.compile("<PackageReference\\b[^>]*?\\bInclude\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    static List<Dependency> csproj(String raw) {
        String s = blank(raw, XML_COMMENT);
        Lines lines = new Lines(raw);
        List<Dependency> out = new ArrayList<>();
        Matcher sdk = CSPROJ_SDK.matcher(s);
        if (sdk.find()) {
            String value = sdk.group(1);
            int slash = value.indexOf('/');     // "Microsoft.NET.Sdk.Web/8.0.0": the part after / is a version
            out.add(lines.at(slash < 0 ? value : value.substring(0, slash), sdk.start(1)));
        }
        Matcher pkg = CSPROJ_PACKAGE.matcher(s);
        while (pkg.find()) {
            if (!pkg.group(1).contains("$(")) {
                out.add(lines.at(pkg.group(1), pkg.start(1)));
            }
        }
        out.sort((a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.column(), b.column()));
        return out;
    }
}
