package org.kde.kdeconnect.plugins.clipboard;

interface IClipboardMonitorCallback {
    void onClipboardTextChanged(String text, boolean isSensitive);
}
