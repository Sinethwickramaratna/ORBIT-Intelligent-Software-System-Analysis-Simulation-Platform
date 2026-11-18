package com.orbit.backend.deps.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NuGet versions ({@code 1.2.3}, {@code 1.2.3.4}, {@code 1.0.0-beta.2}) and version ranges: {@code 1.0} (at least 1.0),
 * {@code [1.0]} (exactly), {@code [1.0,2.0)}, {@code (,1.0]}, and floating versions {@code 1.*} / {@code *}.
 * NuGet restores the LOWEST version of a range (the highest for a floating version).
 */
public final class NuGetVersion {

    private NuGetVersion() {
    }

    public record Version(long[] parts, String pre) implements Comparable<Version> {
        @Override
        public int compareTo(Version o) {
            for (int i = 0; i < 4; i++) {
                int c = Long.compare(i < parts.length ? parts[i] : 0, i < o.parts.length ? o.parts[i] : 0);
                if (c != 0) {
                    return c;
                }
            }
            if (pre == null || o.pre == null) {
                return pre == null ? (o.pre == null ? 0 : 1) : -1;
            }
            String[] a = pre.split("\\.");
            String[] b = o.pre.split("\\.");
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                boolean an = a[i].matches("\\d+");
                boolean bn = b[i].matches("\\d+");
                int c = an && bn ? Long.compare(Long.parseLong(a[i]), Long.parseLong(b[i]))
                        : an != bn ? (an ? -1 : 1) : a[i].compareToIgnoreCase(b[i]);
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a.length, b.length);
        }

        /** NuGet's normalised folder name: lower case, 4th part only when non-zero. */
        public String normalized() {
            StringBuilder sb = new StringBuilder();
            int n = parts.length >= 4 && parts[3] != 0 ? 4 : 3;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append('.');
                }
                sb.append(i < parts.length ? parts[i] : 0);
            }
            if (pre != null) {
                sb.append('-').append(pre.toLowerCase(Locale.ROOT));
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Version v && compareTo(v) == 0;
        }

        @Override
        public int hashCode() {
            return normalized().hashCode();
        }

        @Override
        public String toString() {
            return normalized();
        }
    }

    private static final Pattern VERSION = Pattern.compile("^\\s*(\\d+(?:\\.\\d+){0,3})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?\\s*$");

    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        String[] p = m.group(1).split("\\.");
        long[] parts = new long[p.length];
        for (int i = 0; i < p.length; i++) {
            parts[i] = Long.parseLong(p[i]);
        }
        return Optional.of(new Version(parts, m.group(2)));
    }

    /** A range; {@link #pick} chooses the version NuGet would restore. */
    public record Range(Version min, boolean minInclusive, Version max, boolean maxInclusive, String floatPrefix) {

        public boolean test(Version v) {
            if (floatPrefix != null) {
                return v.pre() == null && (floatPrefix.isEmpty() || v.normalized().startsWith(floatPrefix)
                        || (v.normalized() + ".").startsWith(floatPrefix));
            }
            if (min != null) {
                int c = v.compareTo(min);
                if (c < 0 || (c == 0 && !minInclusive)) {
                    return false;
                }
            }
            if (max != null) {
                int c = v.compareTo(max);
                if (c > 0 || (c == 0 && !maxInclusive)) {
                    return false;
                }
            }
            // pre-releases only when the range itself names one
            return v.pre() == null || (min != null && min.pre() != null) || (max != null && max.pre() != null);
        }

        public boolean test(String version) {
            return parse(version).map(this::test).orElse(false);
        }

        public Optional<String> pick(Collection<String> versions) {
            String best = null;
            Version bestV = null;
            for (String s : versions) {
                Optional<Version> v = parse(s);
                if (v.isEmpty() || !test(v.get())) {
                    continue;
                }
                boolean better = bestV == null || (floatPrefix != null ? v.get().compareTo(bestV) > 0 : v.get().compareTo(bestV) < 0);
                if (better) {
                    bestV = v.get();
                    best = s;
                }
            }
            return Optional.ofNullable(best);
        }
    }

    public static Optional<Range> range(String text) {
        if (text == null || text.isBlank()) {
            return Optional.of(new Range(null, true, null, true, ""));
        }
        String t = text.trim();
        if (t.endsWith("*")) {
            String prefix = t.substring(0, t.length() - 1).toLowerCase(Locale.ROOT);
            return Optional.of(new Range(null, true, null, true, prefix));
        }
        if (!t.startsWith("[") && !t.startsWith("(")) {
            return parse(t).map(v -> new Range(v, true, null, true, null));
        }
        if (t.length() < 3 || !(t.endsWith("]") || t.endsWith(")"))) {
            return Optional.empty();
        }
        boolean minInc = t.charAt(0) == '[';
        boolean maxInc = t.endsWith("]");
        String inner = t.substring(1, t.length() - 1);
        List<String> bounds = new ArrayList<>(List.of(inner.split(",", -1)));
        if (bounds.size() == 1) {
            Optional<Version> v = parse(bounds.get(0));
            return v.map(x -> new Range(x, true, x, true, null));
        }
        if (bounds.size() != 2) {
            return Optional.empty();
        }
        Version min = bounds.get(0).isBlank() ? null : parse(bounds.get(0)).orElse(null);
        Version max = bounds.get(1).isBlank() ? null : parse(bounds.get(1)).orElse(null);
        if ((min == null && !bounds.get(0).isBlank()) || (max == null && !bounds.get(1).isBlank())) {
            return Optional.empty();
        }
        return Optional.of(new Range(min, minInc, max, maxInc, null));
    }
}
