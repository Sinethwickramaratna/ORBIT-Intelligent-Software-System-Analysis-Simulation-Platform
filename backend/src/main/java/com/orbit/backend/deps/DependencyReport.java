package com.orbit.backend.deps;

import java.util.List;

/**
 * The outcome of a dependency check of one project folder: one {@link Unit} per manifest group (a package.json, a
 * pom.xml module, the Python manifests of one folder, ...). Paths are relative to the project folder, {@code /}
 * separated.
 */
public record DependencyReport(Status status, List<Unit> units) {

    /** Overall result shown as the green check or the red X. */
    public enum Status {
        /** Everything declared is present. */
        INSTALLED,
        /** At least one dependency must be set up. */
        MISSING,
        /** The project declares no dependencies at all (nothing to check: shown green). */
        NO_DEPENDENCIES
    }

    /** One checked dependency. {@code required} is the version requirement as written (may be null). */
    public record Item(String name, String required, String installed, String scope, DependencyState state, String note) {
    }

    /**
     * One manifest group.
     *
     * @param manifest        the manifest file(s), comma separated when several files of one folder form the group
     * @param installLocation where Setup Dependencies puts the files (relative to the project), null when nothing is installed
     * @param message         a sentence for the user (for example why a group cannot be set up), may be null
     */
    public record Unit(Ecosystem ecosystem, String manifest, String installLocation, Status status, String message,
                       List<Item> items) {

        public long missingCount() {
            return items.stream().filter(i -> i.state().needsSetup()).count();
        }

        public static Unit of(Ecosystem ecosystem, String manifest, String installLocation, String message, List<Item> items,
                              boolean extraMissing) {
            boolean missing = extraMissing || items.stream().anyMatch(i -> i.state().needsSetup());
            Status s = missing ? Status.MISSING : items.isEmpty() ? Status.NO_DEPENDENCIES : Status.INSTALLED;
            return new Unit(ecosystem, manifest, installLocation, s, message, List.copyOf(items));
        }
    }

    public static DependencyReport of(List<Unit> units) {
        boolean missing = units.stream().anyMatch(u -> u.status() == Status.MISSING);
        boolean any = units.stream().anyMatch(u -> u.status() != Status.NO_DEPENDENCIES);
        return new DependencyReport(missing ? Status.MISSING : any ? Status.INSTALLED : Status.NO_DEPENDENCIES,
                List.copyOf(units));
    }

    public long totalCount() {
        return units.stream().mapToLong(u -> u.items().size()).sum();
    }

    public long missingCount() {
        return units.stream().mapToLong(Unit::missingCount).sum();
    }
}
