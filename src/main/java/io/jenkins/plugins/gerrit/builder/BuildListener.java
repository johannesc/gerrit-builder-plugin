package io.jenkins.plugins.gerrit.builder;

import javax.annotation.Nonnull;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

@Extension
public final class BuildListener extends RunListener<Run> {
    @Override
    public synchronized void onCompleted(@Nonnull Run r, @Nonnull TaskListener listener) {
        PluginImpl plugin = PluginImpl.getInstance();
        if (plugin != null) {
            plugin.onBuildCompleted(r);
        }
    }

    @Override
    public synchronized void onStarted(Run r, TaskListener listener) {
        PluginImpl plugin = PluginImpl.getInstance();
        if (plugin != null) {
            plugin.onBuildStarted(r);
        }
    }
}
