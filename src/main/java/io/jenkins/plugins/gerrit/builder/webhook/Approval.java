package io.jenkins.plugins.gerrit.builder.webhook;

public class Approval {
    String type;
    String description;
    int value;
    int oldValue;

    public Approval(String type, String description, int value, int oldValue) {
      this.type = type;
      this.description = description;
      this.value = value;
      this.oldValue = oldValue;
    }

    @Override
    public String toString() {
      return type + "-" + description + "-" + value + "-" + oldValue;
    }
}
