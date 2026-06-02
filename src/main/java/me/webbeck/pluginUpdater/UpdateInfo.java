package me.webbeck.pluginUpdater;

import java.util.ArrayList;
import java.util.List;

public class UpdateInfo {
    public final String pluginName;
    public final String oldVersion;
    public final String newVersion;
    public final String downloadUrl;
    public final String fileName;
    public final List<String> requiredDependencies = new ArrayList<>();

    public UpdateInfo(String pluginName, String oldVersion, String newVersion, String downloadUrl, String fileName) {
        this.pluginName = pluginName;
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
        this.downloadUrl = downloadUrl;
        this.fileName = fileName;
    }
}
