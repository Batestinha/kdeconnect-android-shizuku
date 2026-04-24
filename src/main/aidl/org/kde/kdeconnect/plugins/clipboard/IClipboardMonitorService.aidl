package org.kde.kdeconnect.plugins.clipboard;

import org.kde.kdeconnect.plugins.clipboard.IClipboardMonitorCallback;

interface IClipboardMonitorService {
    void start(IClipboardMonitorCallback callback) = 1;
    void stop() = 2;
    void destroy() = 16777114;
}
