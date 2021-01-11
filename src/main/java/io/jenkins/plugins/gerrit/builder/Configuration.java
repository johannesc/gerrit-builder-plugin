package io.jenkins.plugins.gerrit.builder;

import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.Queue;
import hudson.model.queue.Tasks;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;

@Extension
public class Configuration extends GlobalConfiguration {
    public Configuration() {
        load();
    }

    public static Configuration get() {
        return ExtensionList.lookupSingleton(Configuration.class);
    }

    private String credentialsId;
    private String gerritServerUrl;
    private boolean insecureHttps;

    public boolean getInsecureHttps() {
        return insecureHttps;
    }

    @DataBoundSetter
    public void setInsecureHttps(boolean insecureHttps) {
        this.insecureHttps = insecureHttps;
        PluginImpl.getInstance().configChanged();
    }

    @DataBoundSetter
    public void setGerritServerUrl(String gerritServerUrl) {
        this.gerritServerUrl = gerritServerUrl;
        save();
        PluginImpl.getInstance().configChanged();
    }

    public String getGerritServerUrl() {
        return gerritServerUrl;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId;
        save();
        PluginImpl.getInstance().configChanged();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public FormValidation doCheckGerritServerUrl(@QueryParameter String value) {
        boolean ok = true;
        if (StringUtils.isEmpty(value)) {
            ok = false;
        }
        try {
            new URL(value);
        } catch (MalformedURLException e) {
            ok = false;
        }
        if (ok) {
            return FormValidation.ok();
        } else {
            return FormValidation.warning("Please specify a valid server URL.");
        }
    }

    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item context, @QueryParameter String remote,
            @QueryParameter String credentialsId) {
        if (context == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                || context != null && !context.hasPermission(Item.EXTENDED_READ)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }
        return new StandardListBoxModel().includeEmptyValue()
                .includeMatchingAs(
                        context instanceof Queue.Task ? Tasks.getAuthenticationOf((Queue.Task) context) : ACL.SYSTEM,
                        context, StandardUsernameCredentials.class, URIRequirementBuilder.fromUri(remote).build(),
                        GitClient.CREDENTIALS_MATCHER)
                .includeCurrentValue(credentialsId);
    }

    public FormValidation doCheckCredentialsId(@AncestorInPath Item context, @QueryParameter String remote,
            @QueryParameter String value) {
        return FormValidation.ok();
    }
}
