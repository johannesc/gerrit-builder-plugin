package io.jenkins.plugins.gerrit.builder.fetcher;

import java.util.Objects;

import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.LabelInfo;

/**
 * This class represents a gerrit change
 */
public class GerritChange implements Comparable<GerritChange> {
    public int _number;
    public int patchset;
    public String id;
    public String subject;
    public boolean tested;

    public GerritChange(ChangeInfo changeInfo) {
        this._number = changeInfo._number;
        this.patchset = changeInfo.revisions.get(changeInfo.currentRevision)._number;
        this.id = changeInfo.id;
        this.subject = changeInfo.subject;

        // TODO this should be customizable, not everyone uses "Verified"
        LabelInfo verified = changeInfo.labels.get("Verified");
        if (verified != null) {
            this.tested = verified.approved != null || verified.disliked != null || verified.rejected != null;
        } else {
            this.tested = false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(_number, patchset);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (!(o instanceof GerritChange)) {
            return false;
        }
        GerritChange gerritChange = (GerritChange) o;
        return _number == gerritChange._number && patchset == gerritChange.patchset;
    }

    @Override
    public String toString() {
        return "" + _number + "-" + patchset;
    }

    public String toDebugString() {
        return toString() + "(" + subject + ")";
    }

    @Override
    public int compareTo(GerritChange gerritChange) {
        return _number - gerritChange._number;
    }
}