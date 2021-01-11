package io.jenkins.plugins.gerrit.builder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.jenkins.plugins.gerrit.builder.fetcher.Build;

/**
 * A class that keep track of ongoing builds given a submit group.
 */
public class SubmitGroupBuildStatus {
    private Set<Build> triggeredBuilds;
    private Set<Build> startedBuilds;
    private Set<Build> successBuilds;
    private Set<Build> failedBuilds;
    private Set<String> buildURLs;

    boolean voted = false;

    public SubmitGroupBuildStatus() {
        triggeredBuilds = new HashSet<Build>();
        startedBuilds = new HashSet<Build>();
        successBuilds = new HashSet<Build>();
        failedBuilds = new HashSet<Build>();
        buildURLs = new HashSet<String>();
    }

    public boolean containsBuild(Build build) {
        return triggeredBuilds.contains(build) || startedBuilds.contains(build) || successBuilds.contains(build)
                || failedBuilds.contains(build);
    }

    public void onTriggered(Build build) {
        assert !containsBuild(build);
        triggeredBuilds.add(build);
    }

    public void onStarted(Build build) {
        assert triggeredBuilds.contains(build);
        triggeredBuilds.remove(build);
        startedBuilds.add(build);
    }

    public void onSuccess(Build build) {
        assert startedBuilds.contains(build);
        successBuilds.add(build);
        startedBuilds.remove(build);
    }

    public void onFailure(Build build) {
        assert startedBuilds.contains(build);
        failedBuilds.add(build);
        startedBuilds.remove(build);
    }

    public void onVoted() {
        this.voted = true;
    }

    public boolean haveVoted() {
        return voted;
    }

    public boolean completed() {
        return triggeredBuilds.isEmpty() && startedBuilds.isEmpty();
    }

    public boolean failed() {
        return triggeredBuilds.isEmpty() && startedBuilds.isEmpty() && !failedBuilds.isEmpty();
    }

    public boolean success() {
        return triggeredBuilds.isEmpty() && startedBuilds.isEmpty() && failedBuilds.isEmpty();
    }

    public boolean allBuildsStarted() {
        return triggeredBuilds.isEmpty();
    }

    public void addBuildURL(String URL) {
        buildURLs.add(URL);
    }

    public Set<String> getBuildURLs() {
        return Collections.unmodifiableSet(buildURLs);
    }

    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();
        buffer.append("Triggered builds\n");
        for (Build build : triggeredBuilds) {
            buffer.append("   ").append(build).append("\n");
        }
        buffer.append("Started builds\n");
        for (Build build : startedBuilds) {
            buffer.append("   ").append(build).append("\n");
        }
        buffer.append("Success builds\n");
        for (Build build : successBuilds) {
            buffer.append("   ").append(build).append("\n");
        }
        buffer.append("Failed builds\n");
        for (Build build : failedBuilds) {
            buffer.append("   ").append(build).append("\n");
        }
        return buffer.toString();
    }
}
