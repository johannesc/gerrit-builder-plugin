package io.jenkins.plugins.gerrit.builder;

import hudson.triggers.SCMTrigger.SCMTriggerCause;
import io.jenkins.plugins.gerrit.builder.fetcher.Build;
import io.jenkins.plugins.gerrit.builder.fetcher.SubmitGroup;

public class GerritBuilderCause extends SCMTriggerCause {
    private SubmitGroup submitGroup;
    private Build build;

    public GerritBuilderCause(SubmitGroup submitGroup, Build build) {
        super("");
        this.submitGroup = submitGroup;
        this.build = build;
    }

    public SubmitGroup getSubmitGroup() {
        return submitGroup;
    }

    public Build getBuild() {
        return build;
    }

}
