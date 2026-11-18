package com.orbit.backend.deps;

import java.io.IOException;
import java.util.List;

/** Check and setup for one ecosystem. */
public interface EcosystemSupport {

    /** A manifest group of the project: what one check row and one setup step stand for. */
    record UnitSpec(Ecosystem ecosystem, String key, List<String> manifests) {
        public String title() {
            return ecosystem.label() + " - " + String.join(", ", manifests);
        }
    }

    Ecosystem ecosystem();

    List<UnitSpec> units(ProjectFiles files);

    DependencyReport.Unit check(ProjectFiles files, UnitSpec unit, InstallContext ctx);

    /**
     * Downloads what the unit is missing into the project folder. Throws when the unit could not be set up at all;
     * single packages that fail are reported through {@link InstallContext#fail}.
     */
    void install(ProjectFiles files, UnitSpec unit, InstallContext ctx) throws IOException;
}
