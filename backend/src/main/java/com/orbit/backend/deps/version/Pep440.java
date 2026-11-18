package com.orbit.backend.deps.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python versions, version specifiers and environment markers (PEP 440 / PEP 508), as far as pip needs them to pick
 * a release: {@code ==1.2.*}, {@code ~=2.1}, {@code >=3,<4}, {@code !=1.5}, and markers such as
 * {@code python_version < "3.11" and sys_platform == "win32"}.
 */
public final class Pep440 {

    private Pep440() {
    }

    /** A normalised version. */
    public record Version(int epoch, List<Long> release, String preLetter, long preNum, Long post, Long dev, String local,
                          String text) implements Comparable<Version> {

        public boolean isPrerelease() {
            return preLetter != null || dev != null;
        }

        public Version publicVersion() {
            return local == null ? this : new Version(epoch, release, preLetter, preNum, post, dev, null, text);
        }

        long rel(int i) {
            return i < release.size() ? release.get(i) : 0L;
        }

        @Override
        public int compareTo(Version o) {
            int c = Integer.compare(epoch, o.epoch);
            if (c != 0) {
                return c;
            }
            int n = Math.max(release.size(), o.release.size());
            for (int i = 0; i < n; i++) {
                c = Long.compare(rel(i), o.rel(i));
                if (c != 0) {
                    return c;
                }
            }
            c = Long.compare(preKey(), o.preKey());
            if (c == 0 && preLetter != null && o.preLetter != null) {
                c = Long.compare(preNum, o.preNum);
            }
            if (c != 0) {
                return c;
            }
            c = Long.compare(post == null ? -1 : post, o.post == null ? -1 : o.post);
            if (c != 0) {
                return c;
            }
            c = Long.compare(dev == null ? Long.MAX_VALUE : dev, o.dev == null ? Long.MAX_VALUE : o.dev);
            if (c != 0) {
                return c;
            }
            return compareLocal(local, o.local);
        }

        /** dev-only release < a < b < rc < final. */
        private long preKey() {
            if (preLetter == null) {
                return post == null && dev != null ? -1 : 3;
            }
            return switch (preLetter) {
                case "a" -> 0;
                case "b" -> 1;
                default -> 2;
            };
        }

        private static int compareLocal(String a, String b) {
            if (a == null || b == null) {
                return a == null ? (b == null ? 0 : -1) : 1;
            }
            String[] x = a.split("\\.");
            String[] y = b.split("\\.");
            for (int i = 0; i < Math.min(x.length, y.length); i++) {
                boolean xn = x[i].matches("\\d+");
                boolean yn = y[i].matches("\\d+");
                int c = xn && yn ? Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]))
                        : xn != yn ? (xn ? 1 : -1) : x[i].compareTo(y[i]);
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(x.length, y.length);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final Pattern VERSION = Pattern.compile(
            "^\\s*v?(?:(\\d+)!)?(\\d+(?:\\.\\d+)*)"
                    + "(?:[-_.]?(a|b|c|rc|alpha|beta|pre|preview)[-_.]?(\\d+)?)?"
                    + "(?:-(\\d+)|[-_.]?(post|rev|r)[-_.]?(\\d+)?)?"
                    + "(?:[-_.]?(dev)[-_.]?(\\d+)?)?"
                    + "(?:\\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?\\s*$", Pattern.CASE_INSENSITIVE);

    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            int epoch = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
            List<Long> release = new ArrayList<>();
            for (String p : m.group(2).split("\\.")) {
                release.add(Long.parseLong(p));
            }
            String pre = null;
            long preNum = 0;
            if (m.group(3) != null) {
                String l = m.group(3).toLowerCase(Locale.ROOT);
                pre = switch (l) {
                    case "alpha", "a" -> "a";
                    case "beta", "b" -> "b";
                    default -> "rc";
                };
                preNum = m.group(4) == null ? 0 : Long.parseLong(m.group(4));
            }
            Long post = null;
            if (m.group(5) != null) {
                post = Long.parseLong(m.group(5));
            } else if (m.group(6) != null) {
                post = m.group(7) == null ? 0L : Long.parseLong(m.group(7));
            }
            Long dev = m.group(8) == null ? null : (m.group(9) == null ? 0L : Long.parseLong(m.group(9)));
            String local = m.group(10) == null ? null : m.group(10).toLowerCase(Locale.ROOT).replaceAll("[-_]", ".");
            return Optional.of(new Version(epoch, List.copyOf(release), pre, preNum, post, dev, local, text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------ specifiers

    private record Clause(String op, String version, Version parsed, boolean wildcard) {
    }

    /** A specifier set such as {@code >=1.0,!=1.3.*,<2}; the empty set accepts every final release. */
    public static final class Specifier {
        private final List<Clause> clauses;
        private final String text;

        private Specifier(List<Clause> clauses, String text) {
            this.clauses = clauses;
            this.text = text;
        }

        public boolean isEmpty() {
            return clauses.isEmpty();
        }

        @Override
        public String toString() {
            return text;
        }

        /** Whether the set mentions a pre-release, which allows pre-releases to match. */
        boolean allowsPre() {
            return clauses.stream().anyMatch(c -> c.parsed() != null && c.parsed().isPrerelease());
        }

        public boolean test(String version) {
            return parse(version).map(v -> test(v, false)).orElse(false);
        }

        public boolean test(Version v, boolean prereleases) {
            if (v.isPrerelease() && !prereleases && !allowsPre()) {
                return false;
            }
            for (Clause c : clauses) {
                if (!clause(c, v)) {
                    return false;
                }
            }
            return true;
        }

        public Specifier and(Specifier other) {
            List<Clause> all = new ArrayList<>(clauses);
            all.addAll(other.clauses);
            String t = text.isEmpty() ? other.text : other.text.isEmpty() ? text : text + "," + other.text;
            return new Specifier(all, t);
        }

        /** The highest version that matches; pre-releases only when nothing else does. */
        public Optional<String> best(Collection<String> versions) {
            String best = null;
            Version bestV = null;
            for (boolean pre : new boolean[]{false, true}) {
                for (String s : versions) {
                    Optional<Version> v = parse(s);
                    if (v.isPresent() && test(v.get(), pre) && (bestV == null || v.get().compareTo(bestV) > 0)) {
                        bestV = v.get();
                        best = s;
                    }
                }
                if (best != null) {
                    break;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    public static final Specifier ANY = new Specifier(List.of(), "");

    private static final Pattern CLAUSE = Pattern.compile("^\\s*(~=|===|==|!=|<=|>=|<|>)\\s*([^\\s,]+)\\s*$");

    /** Parses {@code >=1.0, <2}; throws {@link IllegalArgumentException} for something that is not a specifier. */
    public static Specifier specifier(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return ANY;
        }
        List<Clause> list = new ArrayList<>();
        for (String part : t.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            Matcher m = CLAUSE.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException("Invalid version specifier: " + part.trim());
            }
            String op = m.group(1);
            String ver = m.group(2);
            boolean wildcard = ver.endsWith(".*");
            String core = wildcard ? ver.substring(0, ver.length() - 2) : ver;
            Version parsed = op.equals("===") ? parse(core).orElse(null) : parse(core)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid version: " + ver));
            list.add(new Clause(op, core, parsed, wildcard));
        }
        return new Specifier(List.copyOf(list), t);
    }

    public static Optional<Specifier> trySpecifier(String text) {
        try {
            return Optional.of(specifier(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean clause(Clause c, Version v) {
        Version s = c.parsed();
        switch (c.op()) {
            case "===":
                return v.text().equalsIgnoreCase(c.version());
            case "==":
                return c.wildcard() ? prefixMatch(s, v) : (s.local() == null ? v.publicVersion() : v).compareTo(s) == 0;
            case "!=":
                return c.wildcard() ? !prefixMatch(s, v) : (s.local() == null ? v.publicVersion() : v).compareTo(s) != 0;
            case "~=": {
                if (v.compareTo(s) < 0) {
                    return false;
                }
                List<Long> prefix = s.release().subList(0, Math.max(1, s.release().size() - 1));
                return prefixMatch(new Version(s.epoch(), prefix, null, 0, null, null, null, ""), v);
            }
            case "<=":
                return v.publicVersion().compareTo(s) <= 0;
            case ">=":
                return v.publicVersion().compareTo(s) >= 0;
            case "<": {
                Version pv = v.publicVersion();
                if (pv.compareTo(s) >= 0) {
                    return false;
                }
                // <3.0 does not accept 3.0rc1 unless the specifier is a pre-release itself
                return s.isPrerelease() || !pv.isPrerelease() || !sameRelease(pv, s);
            }
            case ">": {
                Version pv = v.publicVersion();
                if (pv.compareTo(s) <= 0) {
                    return false;
                }
                // >1.0 does not accept 1.0.post1 unless the specifier is a post-release itself
                return s.post() != null || pv.post() == null || !sameRelease(pv, s);
            }
            default:
                return false;
        }
    }

    private static boolean sameRelease(Version a, Version b) {
        int n = Math.max(a.release().size(), b.release().size());
        for (int i = 0; i < n; i++) {
            if (a.rel(i) != b.rel(i)) {
                return false;
            }
        }
        return a.epoch() == b.epoch();
    }

    private static boolean prefixMatch(Version prefix, Version v) {
        if (prefix.epoch() != v.epoch()) {
            return false;
        }
        for (int i = 0; i < prefix.release().size(); i++) {
            if (prefix.release().get(i) != v.rel(i)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------ requirements

    /** A PEP 508 requirement: {@code name[extra1,extra2] (specifier) ; marker} or {@code name @ url}. */
    public record Requirement(String name, Set<String> extras, Specifier specifier, String marker, String url) {
    }

    private static final Pattern REQUIREMENT = Pattern.compile(
            "^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)\\s*(?:\\[([^\\]]*)\\])?\\s*(.*)$");

    /** Parses one requirement; empty for something that is not a PEP 508 requirement. */
    public static Optional<Requirement> requirement(String line) {
        String marker = null;
        String body = line;
        int semi = indexOfMarker(line);
        if (semi >= 0) {
            marker = line.substring(semi + 1).trim();
            body = line.substring(0, semi);
        }
        Matcher m = REQUIREMENT.matcher(body);
        if (!m.matches()) {
            return Optional.empty();
        }
        Set<String> extras = new java.util.LinkedHashSet<>();
        if (m.group(2) != null) {
            for (String e : m.group(2).split(",")) {
                if (!e.isBlank()) {
                    extras.add(normalizeName(e));
                }
            }
        }
        String rest = m.group(3).trim();
        if (rest.startsWith("@")) {
            return Optional.of(new Requirement(m.group(1), extras, ANY, marker, rest.substring(1).trim()));
        }
        if (rest.startsWith("(") && rest.endsWith(")")) {
            rest = rest.substring(1, rest.length() - 1);
        }
        Optional<Specifier> spec = trySpecifier(rest);
        String mk = marker;
        return spec.map(s -> new Requirement(m.group(1), Set.copyOf(extras), s, mk, null));
    }

    /** The ";" that starts the marker (a URL requirement may contain ";" inside the URL before a space). */
    private static int indexOfMarker(String line) {
        int at = line.indexOf('@');
        int from = 0;
        if (at >= 0) {
            int space = line.indexOf(' ', at + 2);
            from = space < 0 ? line.length() : space;
        }
        return line.indexOf(';', from);
    }

    /** PEP 503 normalised name: lower case, runs of {@code -_.} become one {@code -}. */
    public static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    // ------------------------------------------------------------------------------------------ markers

    /**
     * Evaluates an environment marker. Unknown variables evaluate as empty strings; a marker that cannot be parsed
     * counts as true so that a requirement is not silently dropped.
     */
    public static boolean marker(String marker, Map<String, String> env, Set<String> extras) {
        if (marker == null || marker.isBlank()) {
            return true;
        }
        try {
            MarkerParser p = new MarkerParser(marker, env, extras);
            boolean r = p.or();
            p.skipSpace();
            return p.pos < marker.length() || r;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static final class MarkerParser {
        private final String s;
        private final Map<String, String> env;
        private final Set<String> extras;
        int pos;

        MarkerParser(String s, Map<String, String> env, Set<String> extras) {
            this.s = s;
            this.env = env;
            this.extras = extras;
        }

        void skipSpace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean word(String w) {
            skipSpace();
            if (s.regionMatches(pos, w, 0, w.length())
                    && (pos + w.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(pos + w.length())))) {
                pos += w.length();
                return true;
            }
            return false;
        }

        boolean or() {
            boolean r = and();
            while (word("or")) {
                boolean b = and();
                r = r || b;
            }
            return r;
        }

        boolean and() {
            boolean r = atom();
            while (word("and")) {
                boolean b = atom();
                r = r && b;
            }
            return r;
        }

        boolean atom() {
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                boolean r = or();
                skipSpace();
                if (pos < s.length() && s.charAt(pos) == ')') {
                    pos++;
                }
                return r;
            }
            Value left = value();
            String op = operator();
            Value right = value();
            return compare(left, op, right);
        }

        record Value(String text, boolean variable, String name) {
        }

        Value value() {
            skipSpace();
            char c = s.charAt(pos);
            if (c == '"' || c == '\'') {
                int end = s.indexOf(c, pos + 1);
                String t = s.substring(pos + 1, end);
                pos = end + 1;
                return new Value(t, false, null);
            }
            int start = pos;
            while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '.')) {
                pos++;
            }
            String name = s.substring(start, pos);
            if (name.isEmpty()) {
                throw new IllegalArgumentException("marker");
            }
            return new Value(env.getOrDefault(name, ""), true, name);
        }

        String operator() {
            skipSpace();
            for (String op : new String[]{"===", "==", "!=", "<=", ">=", "~=", "<", ">"}) {
                if (s.startsWith(op, pos)) {
                    pos += op.length();
                    return op;
                }
            }
            if (word("not")) {
                if (word("in")) {
                    return "not in";
                }
                throw new IllegalArgumentException("marker");
            }
            if (word("in")) {
                return "in";
            }
            throw new IllegalArgumentException("marker");
        }

        boolean compare(Value l, String op, Value r) {
            if ("extra".equals(l.name()) || "extra".equals(r.name())) {
                String wanted = normalizeName("extra".equals(l.name()) ? r.text() : l.text());
                boolean has = extras.contains(wanted);
                return switch (op) {
                    case "==", "===" -> has;
                    case "!=" -> !has;
                    default -> false;
                };
            }
            String a = l.text();
            String b = r.text();
            switch (op) {
                case "in":
                    return b.contains(a);
                case "not in":
                    return !b.contains(a);
                default:
                    break;
            }
            Optional<Version> va = parse(a);
            if (va.isPresent() && parse(b).isPresent() && !op.equals("===")) {
                return trySpecifier(op + b).map(sp -> sp.test(va.get(), true)).orElse(a.equals(b));
            }
            int c = a.compareTo(b);
            return switch (op) {
                case "==", "===" -> c == 0;
                case "!=" -> c != 0;
                case "<" -> c < 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                default -> false;
            };
        }
    }
}
