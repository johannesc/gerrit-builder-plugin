package io.jenkins.plugins.gerrit.builder.scm;

import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.plugins.git.extensions.impl.WipeWorkspace;
import jenkins.plugins.git.traits.GitSCMExtensionTrait;
import jenkins.plugins.git.traits.GitSCMExtensionTraitDescriptor;

public class GerritBuilderTrait extends GitSCMExtensionTrait<WipeWorkspace> {
    @DataBoundConstructor
    public GerritBuilderTrait() {
        super(new WipeWorkspace());
    }

    @Extension
    public static class DescriptorImpl extends GitSCMExtensionTraitDescriptor {
        @Override
        public String getDisplayName() {
            return "GerritBuilderTrait getDisplayName";
        }
    }
}
