package com.orbit.backend.deps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Maven POMs and Gradle build scripts (plus version catalogs) into Maven coordinates. */
final class JvmManifests {

    private JvmManifests() {
    }

    /** {@code group:artifact:version}; version may be null (managed elsewhere). */
    record Coordinate(String group, String artifact, String version, String scope, String type, String classifier) {
        String ga() {
            return group + ":" + artifact;
        }

        @Override
        public String toString() {
            return ga() + (version == null ? "" : ":" + version);
        }
    }

    // ------------------------------------------------------------------------------------------ Maven

    record Pom(String path, String group, String artifact, String version, String packaging, Coordinate parent,
               String parentRelativePath, Map<String, String> properties, List<Coordinate> dependencies,
               Map<String, String> managed, List<Coordinate> imports, List<String> repositories) {
        String gav() {
            return group + ":" + artifact + ":" + version;
        }
    }

    static Optional<Pom> pom(String path, String text) {
        if (text == null) {
            return Optional.empty();
        }
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Optional.empty();
        }
        Element project = doc.getDocumentElement();
        if (project == null || !project.getTagName().equals("project")) {
            return Optional.empty();
        }
        Element parentEl = child(project, "parent");
        Coordinate parent = null;
        String relativePath = null;
        if (parentEl != null) {
            parent = new Coordinate(childText(parentEl, "groupId"), childText(parentEl, "artifactId"),
                    childText(parentEl, "version"), "parent", "pom", null);
            Element rp = child(parentEl, "relativePath");
            relativePath = rp == null ? "../pom.xml" : rp.getTextContent().trim();
        }
        String group = Optional.ofNullable(childText(project, "groupId")).orElse(parent == null ? null : parent.group());
        String version = Optional.ofNullable(childText(project, "version")).orElse(parent == null ? null : parent.version());
        Map<String, String> props = new LinkedHashMap<>();
        Element propsEl = child(project, "properties");
        if (propsEl != null) {
            for (Element p : children(propsEl, null)) {
                props.put(p.getTagName(), p.getTextContent().trim());
            }
        }
        List<Coordinate> deps = new ArrayList<>();
        Element depsEl = child(project, "dependencies");
        if (depsEl != null) {
            for (Element d : children(depsEl, "dependency")) {
                deps.add(coordinate(d));
            }
        }
        Map<String, String> managed = new LinkedHashMap<>();
        List<Coordinate> imports = new ArrayList<>();
        Element dm = child(project, "dependencyManagement");
        Element dmDeps = dm == null ? null : child(dm, "dependencies");
        if (dmDeps != null) {
            for (Element d : children(dmDeps, "dependency")) {
                Coordinate c = coordinate(d);
                if ("import".equals(c.scope())) {
                    imports.add(c);
                } else if (c.version() != null) {
                    managed.put(c.ga(), c.version());
                }
            }
        }
        List<String> repos = new ArrayList<>();
        Element reposEl = child(project, "repositories");
        if (reposEl != null) {
            for (Element r : children(reposEl, "repository")) {
                String url = childText(r, "url");
                if (url != null) {
                    repos.add(url);
                }
            }
        }
        return Optional.of(new Pom(path, group, childText(project, "artifactId"), version,
                Optional.ofNullable(childText(project, "packaging")).orElse("jar"), parent, relativePath,
                props, deps, managed, imports, repos));
    }

    private static Coordinate coordinate(Element d) {
        return new Coordinate(childText(d, "groupId"), childText(d, "artifactId"), childText(d, "version"),
                Optional.ofNullable(childText(d, "scope")).orElse("compile"),
                Optional.ofNullable(childText(d, "type")).orElse("jar"), childText(d, "classifier"));
    }

    private static Element child(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && el.getTagName().equals(name)) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        NodeList list = e.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            if (list.item(i) instanceof Element el && (name == null || el.getTagName().equals(name))) {
                out.add(el);
            }
        }
        return out;
    }

    private static String childText(Element e, String name) {
        Element c = child(e, name);
        if (c == null) {
            return null;
        }
        String t = c.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    /** Replaces {@code ${...}}; null when something cannot be resolved. */
    static String interpolate(String value, Map<String, String> props) {
        if (value == null) {
            return null;
        }
        String cur = value;
        for (int round = 0; round < 10 && cur.contains("${"); round++) {
            Matcher m = PROPERTY.matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean missing = false;
            while (m.find()) {
                String v = props.get(m.group(1));
                if (v == null) {
                    missing = true;
                    v = m.group(0);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            m.appendTail(sb);
            if (missing && sb.toString().equals(cur)) {
                return null;
            }
            cur = sb.toString();
        }
        return cur.contains("${") ? null : cur;
    }

    /** The properties a POM sees: its parents in the project first, then its own, plus project.* values. */
    static Map<String, String> effectiveProperties(Pom pom, Map<String, Pom> byGav) {
        List<Pom> chain = new ArrayList<>();
        Pom cur = pom;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur.path())) {
            chain.add(0, cur);
            cur = cur.parent() == null ? null : byGav.get(cur.parent().group() + ":" + cur.parent().artifact() + ":" + cur.parent().version());
        }
        Map<String, String> props = new HashMap<>();
        for (Pom p : chain) {
            props.putAll(p.properties());
        }
        put(props, "project.groupId", pom.group());
        put(props, "project.artifactId", pom.artifact());
        put(props, "project.version", pom.version());
        put(props, "pom.version", pom.version());
        put(props, "version", pom.version());
        if (pom.parent() != null) {
            put(props, "project.parent.version", pom.parent().version());
            put(props, "project.parent.groupId", pom.parent().group());
        }
        return props;
    }

    /** groupId:artifactId -> version from dependencyManagement of the POM and its parents inside the project. */
    static Map<String, String> effectiveManaged(Pom pom, Map<String, Pom> byGav) {
        Map<String, String> out = new HashMap<>();
        Pom cur = pom;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur.path())) {
            Map<String, String> props = effectiveProperties(cur, byGav);
            for (var e : cur.managed().entrySet()) {
                String ga = interpolate(e.getKey(), props);
                if (ga != null) {
                    out.putIfAbsent(ga, interpolate(e.getValue(), props));
                }
            }
            cur = cur.parent() == null ? null : byGav.get(cur.parent().group() + ":" + cur.parent().artifact() + ":" + cur.parent().version());
        }
        return out;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    // ------------------------------------------------------------------------------------------ Gradle

    record GradleBuild(List<Coordinate> dependencies, List<Coordinate> platforms, List<String> repositories,
                       String springBootVersion, List<String> unresolved, boolean reactNative) {
    }

    /** Version catalog: accessor (libs.spring.boot.starter) -> coordinate; plus bundles and plugin versions. */
    record Catalog(Map<String, Coordinate> libraries, Map<String, List<String>> bundles, Map<String, String[]> plugins) {
        static final Catalog EMPTY = new Catalog(Map.of(), Map.of(), Map.of());
    }

    static String accessor(String alias) {
        return alias.toLowerCase(Locale.ROOT).replaceAll("[-_.]", ".");
    }

    static Catalog catalog(String tomlText) {
        JsonNode t = InstallContext.toml(tomlText).orElse(null);
        if (t == null) {
            return Catalog.EMPTY;
        }
        Map<String, String> versions = new HashMap<>();
        for (var e : t.path("versions").properties()) {
            String v = versionValue(e.getValue());
            if (v != null) {
                versions.put(e.getKey(), v);
            }
        }
        Map<String, Coordinate> libs = new HashMap<>();
        for (var e : t.path("libraries").properties()) {
            JsonNode v = e.getValue();
            String g;
            String a;
            String ver = null;
            if (v.isString()) {
                String[] p = v.asString().split(":");
                if (p.length < 2) {
                    continue;
                }
                g = p[0];
                a = p[1];
                ver = p.length > 2 ? p[2] : null;
            } else {
                String module = InstallContext.text(v, "module");
                if (module != null && module.contains(":")) {
                    g = module.substring(0, module.indexOf(':'));
                    a = module.substring(module.indexOf(':') + 1);
                } else {
                    g = InstallContext.text(v, "group");
                    a = InstallContext.text(v, "name");
                }
                JsonNode vn = v.path("version");
                if (vn.isObject() && vn.has("ref")) {
                    ver = versions.get(vn.path("ref").asString());
                } else if (!vn.isMissingNode()) {
                    ver = versionValue(vn);
                }
            }
            if (g != null && a != null) {
                libs.put(accessor(e.getKey()), new Coordinate(g, a, ver, "compile", "jar", null));
            }
        }
        Map<String, List<String>> bundles = new HashMap<>();
        for (var e : t.path("bundles").properties()) {
            List<String> list = new ArrayList<>();
            e.getValue().forEach(x -> list.add(accessor(x.asString(""))));
            bundles.put(accessor(e.getKey()), list);
        }
        Map<String, String[]> plugins = new HashMap<>();
        for (var e : t.path("plugins").properties()) {
            JsonNode v = e.getValue();
            String id;
            String ver = null;
            if (v.isString()) {
                String[] p = v.asString().split(":");
                id = p[0];
                ver = p.length > 1 ? p[1] : null;
            } else {
                id = InstallContext.text(v, "id");
                JsonNode vn = v.path("version");
                ver = vn.isObject() && vn.has("ref") ? versions.get(vn.path("ref").asString()) : versionValue(vn);
            }
            if (id != null) {
                plugins.put(accessor(e.getKey()), new String[]{id, ver});
            }
        }
        return new Catalog(libs, bundles, plugins);
    }

    private static String versionValue(JsonNode v) {
        if (v == null || v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isValueNode()) {
            return v.asString();
        }
        for (String k : List.of("strictly", "require", "prefer")) {
            if (v.has(k)) {
                return v.path(k).asString().replaceAll("[\\[\\]()]", "").split(",")[0].trim();
            }
        }
        return null;
    }

    private static final Pattern CONFIG_LINE = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9]*)\\s*(?:\\(\\s*|\\s+)(.*)$");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"'$\\r\\n]+)[\"']");
    private static final Pattern MAP_NOTATION = Pattern.compile(
            "group\\s*[:=]\\s*[\"']([^\"']+)[\"']\\s*,\\s*name\\s*[:=]\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*version\\s*[:=]\\s*[\"']([^\"']+)[\"'])?");
    private static final Pattern CATALOG_REF = Pattern.compile("\\blibs\\.((?:bundles\\.)?[A-Za-z0-9_.]+?)(?:\\.get\\(\\))?(?=[\\s),]|$)");
    /** React Native's Gradle plugin: it sets the version of com.facebook.react:* to the react-native npm package's. */
    private static final Pattern REACT_PLUGIN = Pattern.compile(
            "(?:apply\\s+plugin\\s*:\\s*|id\\s*\\(?\\s*)[\"']com\\.facebook\\.react[\"']");
    private static final Pattern BOOT_PLUGIN = Pattern.compile(
            "id\\s*\\(?\\s*[\"']org\\.springframework\\.boot[\"']\\s*\\)?\\s*version\\s*[\"']([^\"']+)[\"']");
    private static final Pattern CATALOG_PLUGIN = Pattern.compile("alias\\s*\\(\\s*libs\\.plugins\\.([A-Za-z0-9_.]+)\\s*\\)");
    private static final Pattern REPO_URL = Pattern.compile(
            "(?:url\\s*[=(]?\\s*(?:uri\\s*\\(\\s*)?|maven\\s*\\(\\s*)[\"'](https://[^\"']+)[\"']");
    private static final Set<String> NOT_CONFIGURATIONS = Set.of("id", "version", "apply", "plugin", "classpath", "include",
            "exclude", "mavenCentral", "google", "maven", "url", "group", "name", "rootProject", "println", "task", "tasks",
            "set", "kotlin", "jvmToolchain", "sourceCompatibility", "targetCompatibility", "alias", "project", "files",
            "fileTree", "listOf", "mapOf", "uri", "def", "val", "var", "if", "return", "description", "archiveFileName");

    /** Reads the dependency declarations of a build.gradle / build.gradle.kts. */
    static GradleBuild gradle(String text, Catalog catalog) {
        String s = DependencyReaderAccess.stripComments(text == null ? "" : text);
        List<Coordinate> deps = new ArrayList<>();
        List<Coordinate> platforms = new ArrayList<>();
        List<String> repos = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        String boot = null;
        Matcher bm = BOOT_PLUGIN.matcher(s);
        if (bm.find()) {
            boot = bm.group(1);
        }
        Matcher cp = CATALOG_PLUGIN.matcher(s);
        while (cp.find()) {
            String[] plugin = catalog.plugins().get(accessor(cp.group(1)));
            if (plugin != null && "org.springframework.boot".equals(plugin[0]) && plugin[1] != null) {
                boot = plugin[1];
            }
        }
        Matcher rm = REPO_URL.matcher(s);
        while (rm.find()) {
            repos.add(rm.group(1));
        }
        int depth = 0;
        boolean inDeps = false;
        int depsDepth = -1;
        boolean inBuildscript = false;
        int buildscriptDepth = -1;
        for (String line : s.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("buildscript")) {
                inBuildscript = true;
                buildscriptDepth = depth;
            }
            if (!inDeps && trimmed.matches("^dependencies\\s*\\{.*")) {
                inDeps = true;
                depsDepth = depth;
            }
            if (inDeps && !inBuildscript) {
                Matcher m = CONFIG_LINE.matcher(line);
                if (m.matches() && !NOT_CONFIGURATIONS.contains(m.group(1)) && !m.group(1).equals("dependencies")) {
                    declaration(m.group(1), m.group(2), catalog, deps, platforms, unresolved);
                }
            }
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (inDeps && depth <= depsDepth) {
                        inDeps = false;
                    }
                    if (inBuildscript && depth <= buildscriptDepth) {
                        inBuildscript = false;
                    }
                }
            }
        }
        return new GradleBuild(deps, platforms, repos, boot, unresolved, REACT_PLUGIN.matcher(s).find());
    }

    private static void declaration(String config, String rest, Catalog catalog, List<Coordinate> deps,
                                    List<Coordinate> platforms, List<String> unresolved) {
        String scope = config.toLowerCase(Locale.ROOT).contains("test") ? "test" : "compile";
        String r = rest.trim();
        boolean platform = r.startsWith("platform(") || r.startsWith("enforcedPlatform(");
        if (r.startsWith("project(") || r.startsWith("files(") || r.startsWith("fileTree(") || r.startsWith("gradleApi(")
                || r.startsWith("localGroovy(") || r.startsWith("gradleTestKit(")) {
            return;
        }
        List<Coordinate> found = new ArrayList<>();
        Matcher mn = MAP_NOTATION.matcher(r);
        if (mn.find()) {
            found.add(new Coordinate(mn.group(1), mn.group(2), mn.group(3), scope, "jar", null));
        } else {
            Matcher q = QUOTED.matcher(r);
            if (q.find()) {
                String value = q.group(1);
                String type = "jar";
                int at = value.indexOf('@');
                if (at > 0) {
                    type = value.substring(at + 1);
                    value = value.substring(0, at);
                }
                String[] p = value.split(":");
                if (p.length >= 2 && !p[0].isBlank() && !p[1].isBlank() && !p[0].contains(" ")) {
                    found.add(new Coordinate(p[0], p[1], p.length > 2 && !p[2].isBlank() ? p[2] : null, scope, type,
                            p.length > 3 ? p[3] : null));
                }
            }
            if (found.isEmpty()) {
                Matcher c = CATALOG_REF.matcher(r);
                if (c.find()) {
                    String ref = accessor(c.group(1));
                    if (ref.startsWith("bundles.")) {
                        for (String lib : catalog.bundles().getOrDefault(ref.substring(8), List.of())) {
                            Coordinate x = catalog.libraries().get(lib);
                            if (x != null) {
                                found.add(new Coordinate(x.group(), x.artifact(), x.version(), scope, "jar", null));
                            }
                        }
                    } else if (!ref.startsWith("plugins.") && !ref.startsWith("versions.")) {
                        Coordinate x = catalog.libraries().get(ref);
                        if (x != null) {
                            found.add(new Coordinate(x.group(), x.artifact(), x.version(), scope, "jar", null));
                        } else {
                            unresolved.add("libs." + c.group(1));
                        }
                    }
                }
            }
        }
        for (Coordinate c : found) {
            if (platform) {
                platforms.add(new Coordinate(c.group(), c.artifact(), c.version(), "import", "pom", null));
            } else {
                deps.add(c);
            }
        }
    }

    /** Access to the comment stripper of the framework scanner's dependency reader. */
    static final class DependencyReaderAccess {
        private DependencyReaderAccess() {
        }

        static String stripComments(String s) {
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
                        if (sb.charAt(k) != '\n') {
                            sb.setCharAt(k, ' ');
                        }
                    }
                    i = stop - 1;
                }
            }
            return sb.toString();
        }
    }
}
