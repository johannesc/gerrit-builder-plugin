package io.jenkins.plugins.gerrit.builder.scm;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.DataBoundConstructor;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.gerrit.extensions.restapi.RestApiException;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.remoting.VirtualChannel;
import io.jenkins.plugins.gerrit.builder.ApiHelper;
import io.jenkins.plugins.gerrit.builder.PluginImpl;
import io.jenkins.plugins.gerrit.builder.fetcher.GerritChangeFetcher;

public class GerritBuilderExtension extends GitSCMExtension {

    @DataBoundConstructor
    public GerritBuilderExtension() {
    }

    private void log(TaskListener listener, String log) {
        listener.getLogger().println(log);
    }

    @Override
    public void onCheckoutCompleted(GitSCM scm, Run<?, ?> build, GitClient git, TaskListener listener)
            throws IOException, InterruptedException, GitException {
        log(listener, "GerritBuilderExtension: onCheckoutCompleted");

        EnvVars env = build.getEnvironment(listener);
        String project = env.get("GERRIT_PROJECT");
        String change = env.get("GERRIT_CHANGE_NUMBER");
        String patchset = env.get("GERRIT_PATCHSET_NUMBER");
        String branch = env.get("GERRIT_BRANCH");

        if (project != null && change != null && patchset != null && branch != null) {
            log(listener, "Download Gerrit change in " + git.getWorkTree());

            PluginImpl plugin = PluginImpl.getInstance();
            String credentialsId = plugin.getConfiguration().getCredentialsId();
            StandardUsernamePasswordCredentials cred = ApiHelper.getCredentials(credentialsId);
            String url = plugin.getConfiguration().getGerritServerUrl();

            // Currently only implemented in "plain" jgit and not using the GitClient API
            // so we do this on the remote machine.
            // In order for this to properly work we need proper credentials.
            git.getWorkTree().act(new ChangeApplier(listener, cred, url, Integer.valueOf(change),
                    Integer.valueOf(patchset), project, branch));
        } else {
            log(listener, "No Gerrit Change to download");
        }
    }

    private static final class ChangeApplier extends jenkins.MasterToSlaveFileCallable<Void> {
        private static final long serialVersionUID = 1L;
        private int change;
        private int patchset;
        private String project;
        private String branch;
        private TaskListener listener;
        private StandardUsernamePasswordCredentials credentials;
        private String url;

        public ChangeApplier(TaskListener listener, StandardUsernamePasswordCredentials credentials, String url,
                int change, int patchset, String project, String branch) {
            this.listener = listener;
            this.credentials = credentials;
            this.url = url;
            this.change = change;
            this.patchset = patchset;
            this.project = project;
            this.branch = branch;
        }

        @Override
        public Void invoke(File workDir, VirtualChannel channel) throws IOException, InterruptedException {
            PrintStream log = listener.getLogger();
            log.println("Downloading Gerrit change(s) on node in directory " + workDir.getAbsolutePath());
            log.println("url:" + url);
            log.println("change:" + change);
            log.println("patchset:" + patchset);
            log.println("project:" + project);
            log.println("branch:" + branch);

            GerritChangeFetcher fetcher = ApiHelper.createFetcher(url, credentials, log);
            try {
                fetcher.prepareForBuild(workDir, project, branch, change, patchset);
            } catch (IOException | GitAPIException | URISyntaxException | ConfigInvalidException | RestApiException e) {
                log.println("Failed downloading Gerrit changes:" + e.toString());
                throw new IOException(e);
            }
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends GitSCMExtensionDescriptor {
        @Override
        public String getDisplayName() {
            return "Trigger build and download changes from Gerrit";
        }
    }

    @Override
    public String toString() {
        return "GerritBuilderExtension toString(){}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return o instanceof GerritBuilderExtension;
    }

    @Override
    public int hashCode() {
        return GerritBuilderExtension.class.hashCode();
    }
}
