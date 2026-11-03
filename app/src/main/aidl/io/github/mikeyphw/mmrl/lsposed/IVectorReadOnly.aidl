package io.github.mikeyphw.mmrl.lsposed;

/** Narrow query + one-operation mutation interface, never arbitrary shell input. */
interface IVectorReadOnly {
    String query(String operation, String modulePackage);
    String mutate(String operation, String modulePackage, String targetPackage);
}
