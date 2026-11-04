package com.mikeyphw.lsposed.hooklab.target;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** One public read-only method probe. No database, arbitrary queries, or writes. */
public final class ProbeProvider extends ContentProvider {
    private static final String AUTHORITY = "com.mikeyphw.lsposed.hooklab.target";
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!AUTHORITY.equals(uri.getAuthority()) || !"/probe".equals(uri.getPath())) {
            throw new IllegalArgumentException("Unknown probe URI");
        }
        MatrixCursor out = new MatrixCursor(new String[]{"value"});
        out.addRow(new Object[]{ProbeValue.readValue()});
        return out;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.lsposed.hooklab.probe"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read-only"); }
}
