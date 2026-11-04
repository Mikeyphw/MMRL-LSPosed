package com.mikeyphw.lsposed.hooklab.target;

/** Never used by production apps. */
public final class ProbeValue {
    private static volatile String baseline = "ORIGINAL_LSPOSED_S08";
    private ProbeValue() {}
    public static synchronized String readValue() { return baseline; }
}
