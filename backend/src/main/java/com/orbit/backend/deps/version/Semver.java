package com.orbit.backend.deps.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic versions and ranges as npm (node-semver) and Dart pub write them: {@code ^1.2.3}, {@code ~1.2},
 * {@code >=1.0.0 <2.0.0}, {@code 1.x}, {@code 1.2.3 - 2.3}, {@code a || b}, {@code *}. Pre-releases only satisfy a range
 * that names a pre-release of the same {@code major.minor.patch}, like npm.
 */
public final class Semver {

    private Semver() {
    }

    /** A parsed version. Build metadata is ignored for ordering. */
    public record Version(long major, long minor, long patch, List<String> pre) implements Comparable<Version> {

        public boolean isPrerelease() {
            return !pre.isEmpty();
        }

        boolean sameTuple(Version o) {
            return major == o.major && minor == o.minor && patch == o.patch;
        }

        @Override
        public int compareTo(Version o) {
            int c = Long.compare(major, o.major);
            if (c == 0) {
                c = Long.compare(minor, o.minor);
            }
            if (c == 0) {
                c = Long.compare(patch, o.patch);
            }
            if (c != 0) {
                return c;
            }
            if (pre.isEmpty() || o.pre.isEmpty()) {
                return Boolean.compare(pre.isEmpty(), o.pre.isEmpty());
            }
            for (int i = 0; i < Math.min(pre.size(), o.pre.size()); i++) {
                String a = pre.get(i);
                String b = o.pre.get(i);
                boolean an = a.matches("\\d+");
                boolean bn = b.matches("\\d+");
                if (an && bn) {
                    c = new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
                } else if (an != bn) {
                    c = an ? -1 : 1;
                } else {
                    c = a.compareTo(b);
                }
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(pre.size(), o.pre.size());
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch + (pre.isEmpty() ? "" : "-" + String.join(".", pre));
        }
    }

    private static final Pattern VERSION = Pattern.compile(
            "^\\s*[v=]*\\s*(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?\\s*$");

    /** A full version ({@code 1.2.3}, {@code v1.2.3-beta.1}); empty when the text is not one. */
    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        try {
            List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
            return Optional.of(new Version(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)), pre));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------ ranges

    private enum Op { LT, LTE, GT, GTE, EQ }

    private record Comparator(Op op, Version v) {
        boolean test(Version x) {
            int c = x.compareTo(v);
            return switch (op) {
                case LT -> c < 0;
                case LTE -> c <= 0;
                case GT -> c > 0;
                case GTE -> c >= 0;
                case EQ -> c == 0;
            };
        }
    }

    /** A parsed range: a list of alternatives ({@code ||}), each a list of comparators that must all hold. */
    public static final class Range {
        private final List<List<Comparator>> sets;

        private Range(List<List<Comparator>> sets) {
            this.sets = sets;
        }

        public boolean test(Version v) {
            for (List<Comparator> set : sets) {
                if (set.stream().allMatch(c -> c.test(v)) && prereleaseAllowed(set, v)) {
                    return true;
                }
            }
            return false;
        }

        public boolean test(String version) {
            return parse(version).map(this::test).orElse(false);
        }

        private static boolean prereleaseAllowed(List<Comparator> set, Version v) {
            if (!v.isPrerelease()) {
                return true;
            }
            for (Comparator c : set) {
                if (c.v().isPrerelease() && !isSentinel(c) && c.v().sameTuple(v)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSentinel(Comparator c) {
            return c.op() == Op.LT && c.v().pre().equals(List.of("0"));
        }

        /** The highest of {@code versions} in this range. */
        public Optional<String> maxSatisfying(Collection<String> versions) {
            Version best = null;
            String bestText = null;
            for (String s : versions) {
                Optional<Version> v = parse(s);
                if (v.isPresent() && test(v.get()) && (best == null || v.get().compareTo(best) > 0)) {
                    best = v.get();
                    bestText = s;
                }
            }
            return Optional.ofNullable(bestText);
        }
    }

    private static final Pattern PARTIAL = Pattern.compile(
            "^[v=]*(\\d+|[xX*])(?:\\.(\\d+|[xX*]))?(?:\\.(\\d+|[xX*]))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    /** A partial version: null fields are wildcards. */
    private record Partial(Long major, Long minor, Long patch, List<String> pre) {
        Version floor() {
            return new Version(major == null ? 0 : major, minor == null ? 0 : minor, patch == null ? 0 : patch,
                    patch == null ? List.of() : pre);
        }
    }

    private static Partial partial(String s) {
        Matcher m = PARTIAL.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid version in range: " + s);
        }
        Long ma = num(m.group(1));
        Long mi = ma == null ? null : num(m.group(2));
        Long pa = mi == null ? null : num(m.group(3));
        List<String> pre = m.group(4) == null ? List.of() : List.of(m.group(4).split("\\."));
        return new Partial(ma, mi, pa, pre);
    }

    private static Long num(String g) {
        if (g == null || g.equalsIgnoreCase("x") || g.equals("*")) {
            return null;
        }
        return Long.parseLong(g);
    }

    private static Version v(long a, long b, long c, boolean sentinel) {
        return new Version(a, b, c, sentinel ? List.of("0") : List.of());
    }

    /**
     * Parses a range. {@code ""}, {@code *}, {@code x}, {@code latest}-free ranges only: dist-tags and URLs are not
     * ranges and throw {@link IllegalArgumentException}.
     */
    public static Range range(String text) {
        String t = text == null ? "" : text.trim();
        List<List<Comparator>> sets = new ArrayList<>();
        for (String alt : t.split("\\|\\|", -1)) {
            sets.add(comparatorSet(alt.trim()));
        }
        return new Range(sets);
    }

    /** Like {@link #range} but empty instead of an exception. */
    public static Optional<Range> tryRange(String text) {
        try {
            return Optional.of(range(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static final Pattern HYPHEN = Pattern.compile("^(\\S+)\\s+-\\s+(\\S+)$");

    private static List<Comparator> comparatorSet(String s) {
        List<Comparator> out = new ArrayList<>();
        Matcher h = HYPHEN.matcher(s);
        if (h.matches()) {
            Partial from = partial(h.group(1));
            Partial to = partial(h.group(2));
            if (from.major() != null) {
                out.add(new Comparator(Op.GTE, from.floor()));
            }
            if (to.major() != null) {
                if (to.minor() == null) {
                    out.add(new Comparator(Op.LT, v(to.major() + 1, 0, 0, true)));
                } else if (to.patch() == null) {
                    out.add(new Comparator(Op.LT, v(to.major(), to.minor() + 1, 0, true)));
                } else {
                    out.add(new Comparator(Op.LTE, to.floor()));
                }
            }
            return out;
        }
        String norm = s.replaceAll("(>=|<=|>|<|=|~>|~|\\^)\\s+", "$1");
        for (String tok : norm.split("\\s+")) {
            if (!tok.isEmpty()) {
                token(tok, out);
            }
        }
        return out;
    }

    private static void token(String tok, List<Comparator> out) {
        String op;
        String rest;
        if (tok.startsWith(">=") || tok.startsWith("<=") || tok.startsWith("~>")) {
            op = tok.substring(0, 2);
            rest = tok.substring(2);
        } else if (tok.startsWith(">") || tok.startsWith("<") || tok.startsWith("=") || tok.startsWith("~") || tok.startsWith("^")) {
            op = tok.substring(0, 1);
            rest = tok.substring(1);
        } else {
            op = "";
            rest = tok;
        }
        Partial p = partial(rest);
        Long ma = p.major();
        Long mi = p.minor();
        Long pa = p.patch();
        switch (op) {
            case "^" -> {
                if (ma == null) {
                    return;
                }
                out.add(new Comparator(Op.GTE, p.floor()));
                if (ma > 0 || mi == null) {
                    out.add(new Comparator(Op.LT, v(ma + 1, 0, 0, true)));
                } else if (mi > 0 || pa == null) {
                    out.add(new Comparator(Op.LT, v(0, mi + 1, 0, true)));
                } else {
                    out.add(new Comparator(Op.LT, v(0, 0, pa + 1, true)));
                }
            }
            case "~", "~>" -> {
                if (ma == null) {
                    return;
                }
                out.add(new Comparator(Op.GTE, p.floor()));
                if (mi == null) {
                    out.add(new Comparator(Op.LT, v(ma + 1, 0, 0, true)));
                } else {
                    out.add(new Comparator(Op.LT, v(ma, mi + 1, 0, true)));
                }
            }
            case ">" -> {
                if (ma == null) {
                    out.add(new Comparator(Op.LT, v(0, 0, 0, true))); // nothing
                } else if (mi == null) {
                    out.add(new Comparator(Op.GTE, v(ma + 1, 0, 0, false)));
                } else if (pa == null) {
                    out.add(new Comparator(Op.GTE, v(ma, mi + 1, 0, false)));
                } else {
                    out.add(new Comparator(Op.GT, p.floor()));
                }
            }
            case ">=" -> {
                if (ma != null) {
                    out.add(new Comparator(Op.GTE, p.floor()));
                }
            }
            case "<" -> {
                if (ma == null) {
                    out.add(new Comparator(Op.LT, v(0, 0, 0, true)));
                } else if (pa == null) {
                    out.add(new Comparator(Op.LT, v(ma, mi == null ? 0 : mi, 0, true)));
                } else {
                    out.add(new Comparator(Op.LT, p.floor()));
                }
            }
            case "<=" -> {
                if (ma == null) {
                    return;
                } else if (mi == null) {
                    out.add(new Comparator(Op.LT, v(ma + 1, 0, 0, true)));
                } else if (pa == null) {
                    out.add(new Comparator(Op.LT, v(ma, mi + 1, 0, true)));
                } else {
                    out.add(new Comparator(Op.LTE, p.floor()));
                }
            }
            default -> { // "" or "="
                if (ma == null) {
                    return;
                }
                if (mi == null) {
                    out.add(new Comparator(Op.GTE, p.floor()));
                    out.add(new Comparator(Op.LT, v(ma + 1, 0, 0, true)));
                } else if (pa == null) {
                    out.add(new Comparator(Op.GTE, p.floor()));
                    out.add(new Comparator(Op.LT, v(ma, mi + 1, 0, true)));
                } else {
                    out.add(new Comparator(Op.EQ, p.floor()));
                }
            }
        }
    }
}
