package com.dwp.platform.contracts.hris.capability.v1;

/** Strict semantic version used to reject major drift and installed-version downgrades. */
public record OptionalCapabilityVersionV1(int major, int minor, int patch)
        implements Comparable<OptionalCapabilityVersionV1> {
    public OptionalCapabilityVersionV1 {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("capability version components must be non-negative");
        }
    }

    @Override
    public int compareTo(OptionalCapabilityVersionV1 other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        int majorOrder = Integer.compare(major, other.major);
        if (majorOrder != 0) {
            return majorOrder;
        }
        int minorOrder = Integer.compare(minor, other.minor);
        return minorOrder != 0 ? minorOrder : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
