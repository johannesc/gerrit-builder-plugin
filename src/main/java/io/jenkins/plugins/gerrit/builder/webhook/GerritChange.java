package io.jenkins.plugins.gerrit.builder.webhook;

public class GerritChange {
    public final int number;

    public GerritChange(int number) {
        this.number = number;
    }
}
