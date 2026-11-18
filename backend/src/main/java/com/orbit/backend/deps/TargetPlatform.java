package com.orbit.backend.deps;

import java.util.Locale;

/**
 * The computer the downloaded dependencies are meant for. When ORBIT runs in Docker the files land on the user's
 * disk (through a bind mount), so platform-specific packages - npm packages with {@code os}/{@code cpu}, Python wheels
 * with native code - must be chosen for the USER's operating system, not for the Linux container.
 */
public record TargetPlatform(Os os, Arch arch, String pythonVersion) {

    public enum Os { WINDOWS, MAC, LINUX }

    public enum Arch { X64, ARM64 }

    public static final String DEFAULT_PYTHON = "3.12";

    /** The platform of the JVM itself (backend running directly on the user's computer). */
    public static TargetPlatform current() {
        return new TargetPlatform(osOf(System.getProperty("os.name", "")), archOf(System.getProperty("os.arch", "")), DEFAULT_PYTHON);
    }

    public static Os osOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("win")) {
            return Os.WINDOWS;
        }
        if (n.startsWith("mac") || n.contains("darwin") || n.equals("osx")) {
            return Os.MAC;
        }
        return Os.LINUX;
    }

    public static Arch archOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("aarch64") || n.contains("arm64") ? Arch.ARM64 : Arch.X64;
    }

    /**
     * Guesses the user's operating system from a host path of a Docker mount: {@code E:/} is Windows,
     * {@code /Users/...} is macOS, anything else Linux. Apple computers are ARM unless configured otherwise.
     */
    public static TargetPlatform fromHostPath(String hostPath) {
        String p = hostPath == null ? "" : hostPath.trim();
        if (p.matches("^[A-Za-z]:.*")) {
            return new TargetPlatform(Os.WINDOWS, Arch.X64, DEFAULT_PYTHON);
        }
        if (p.startsWith("/Users/")) {
            return new TargetPlatform(Os.MAC, Arch.ARM64, DEFAULT_PYTHON);
        }
        return new TargetPlatform(Os.LINUX, Arch.X64, DEFAULT_PYTHON);
    }

    public TargetPlatform withPython(String version) {
        return version == null || version.isBlank() ? this : new TargetPlatform(os, arch, version);
    }

    /** npm's {@code process.platform}. */
    public String npmOs() {
        return switch (os) {
            case WINDOWS -> "win32";
            case MAC -> "darwin";
            case LINUX -> "linux";
        };
    }

    /** npm's {@code process.arch}. */
    public String npmCpu() {
        return arch == Arch.ARM64 ? "arm64" : "x64";
    }

    /** Python's {@code sys.platform}. */
    public String pySysPlatform() {
        return switch (os) {
            case WINDOWS -> "win32";
            case MAC -> "darwin";
            case LINUX -> "linux";
        };
    }

    /** Python's {@code platform.system()}. */
    public String pyPlatformSystem() {
        return switch (os) {
            case WINDOWS -> "Windows";
            case MAC -> "Darwin";
            case LINUX -> "Linux";
        };
    }

    /** Python's {@code platform.machine()}. */
    public String pyMachine() {
        return switch (os) {
            case WINDOWS -> arch == Arch.ARM64 ? "ARM64" : "AMD64";
            case MAC -> arch == Arch.ARM64 ? "arm64" : "x86_64";
            case LINUX -> arch == Arch.ARM64 ? "aarch64" : "x86_64";
        };
    }

    /** "3.12" -> 3, 12 */
    public int[] pythonMajorMinor() {
        String[] p = pythonVersion.split("\\.");
        try {
            return new int[]{Integer.parseInt(p[0]), p.length > 1 ? Integer.parseInt(p[1]) : 0};
        } catch (NumberFormatException e) {
            return new int[]{3, 12};
        }
    }
}
