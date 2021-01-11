package io.jenkins.plugins.gerrit.builder.fetcher;

public class Build {
    public SubmitGroup submitGroup;
    public String project;
    public String branch;

    public Build(SubmitGroup submitGroup, String project, String branch) {
        this.submitGroup = submitGroup;
        this.project = project;
        this.branch = branch;
    }

    @Override
    public String toString() {
        return project + "-" + branch + "-" + submitGroup;
    }

    public String toDebugString() {
        StringBuilder sb = new StringBuilder(project + "-" + branch + "-");
        sb.append(submitGroup.toDebugString());
        return sb.toString();
    }

    public int getChangeNumber() {
        return submitGroup.first()._number;
    }

    public int getPatchSet() {
        return submitGroup.first().patchset;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Build) || (o == null)) {
            return false;
        }
        return toString().equals(o.toString());
    }
}
