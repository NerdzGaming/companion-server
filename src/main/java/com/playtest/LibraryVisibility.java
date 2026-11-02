package com.playtest;

/** Both ordinary and face-down visibility must authorize this viewer; ownership is not permission. */
public final class LibraryVisibility {
    private LibraryVisibility() { }
    static boolean allowed(boolean shownToViewer, boolean faceDownShownToViewer) {
        return shownToViewer && faceDownShownToViewer;
    }
}