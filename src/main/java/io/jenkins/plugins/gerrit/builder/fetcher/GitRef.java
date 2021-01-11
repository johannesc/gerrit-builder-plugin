package io.jenkins.plugins.gerrit.builder.fetcher;

public class GitRef {
    public String name;
    public String revision;

    public GitRef(String name, String revision) {
        this.name = name;
        this.revision = revision;
    }

    @Override
    public String toString() {
        return name + "-" + revision;
    }
}