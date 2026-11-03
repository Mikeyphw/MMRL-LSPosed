package io.github.mikeyphw.mmrl.lsposed;

/** Fixed read-only operations; never accepts arbitrary command strings. */
interface IVectorReadOnly {
    String query(String operation, String modulePackage);
}
