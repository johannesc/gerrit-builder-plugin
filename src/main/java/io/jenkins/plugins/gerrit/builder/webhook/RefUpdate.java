package io.jenkins.plugins.gerrit.builder.webhook;

public class RefUpdate {
  String project;
  String refName;

  public RefUpdate(String project, String refName) {
    this.project = project;
    this.refName = refName;
  }

  @Override
  public String toString() {
    return project + "-" + refName;
  }
}
