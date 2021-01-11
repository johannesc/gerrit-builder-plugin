package io.jenkins.plugins.gerrit.builder.fetcher;

import java.util.List;
import java.util.TreeSet;

import com.google.gerrit.extensions.common.ChangeInfo;

/**
 * A submit group is a group of Gerrit changes that will be submitted
 * together.
 */
public class SubmitGroup extends TreeSet<GerritChange> {
    private static final long serialVersionUID = 1L;

    public SubmitGroup(List<ChangeInfo> changes) {
        for (ChangeInfo change : changes) {
            add(new GerritChange(change));
        }
    }

    public boolean allTested() {
        boolean allVerified = true;
        for (GerritChange gerritChange : this) {
            if (!gerritChange.tested) {
                allVerified = false;
                break;
            }
        }
        return allVerified;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        String sep = "";
        for (GerritChange gerritChange : this) {
            sb.append(sep).append(gerritChange);
            sep = "-";
        }
        return sb.toString();
    }

    public Object toDebugString() {
        StringBuilder sb = new StringBuilder();
        String sep = "";
        for (GerritChange gerritChange : this) {
            sb.append(sep).append(gerritChange.toDebugString());
            sep = "-";
        }
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SubmitGroup) || (o == null)) {
            return false;
        }
        return toString().equals(o.toString());
    }
}