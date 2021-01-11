package io.jenkins.plugins.gerrit.builder;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import org.acegisecurity.Authentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.google.gerrit.extensions.api.GerritApi;
import com.urswolfer.gerrit.client.rest.GerritAuthData;
import com.urswolfer.gerrit.client.rest.GerritRestApiFactory;

import hudson.model.Item;
import io.jenkins.plugins.gerrit.builder.fetcher.GerritChangeFetcher;

public class ApiHelper {
    private static final Logger log = LoggerFactory.getLogger(PluginImpl.class);

    /**
     * Get a StandardUsernamePasswordCredentials given a credentialsId
     * 
     * Must not be called on a remote agent as the CredentialsProvider is not
     * available there.
     * 
     * @param credentialsId
     * @return
     */
    public static StandardUsernamePasswordCredentials getCredentials(String credentialsId) {
        if (credentialsId != null) {
            Item item = null;
            Authentication authentication = null;
            List<DomainRequirement> domainRequirements = null;

            List<StandardUsernamePasswordCredentials> creds = CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class, item, authentication, domainRequirements);

            StandardUsernamePasswordCredentials cred = CredentialsMatchers.firstOrNull(creds,
                    CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId)));
            return cred;
        } else {
            log.info("No credential id found, please configure the Gerrit Builder plugin under"
                    + " \"Manage Jenkins\"/\"Configure System\"");
        }
        return null;
    }

    /**
     * Create a GerritChangeFetcher given an URL and
     * StandardUsernamePasswordCredentials
     * 
     * Can be called on a remote agent.
     *
     * @param url
     * @param credentials
     * @param logger      a PrintStream to use for logging, can be null in which
     *                    case logging is redirected to new log.
     * @return
     */
    public static GerritChangeFetcher createFetcher(String url, StandardUsernamePasswordCredentials credentials,
            PrintStream logger) {
        GerritAuthData.Basic authData = new GerritAuthData.Basic(url, credentials.getUsername(),
                credentials.getPassword().getPlainText());

        GerritRestApiFactory gerritRestApiFactory = new GerritRestApiFactory();
        GerritApi gerritApi = gerritRestApiFactory.create(authData);
        try {
            return new GerritChangeFetcher(gerritApi, logger);
        } catch (IOException e) {
            log.info("Could not create remote gerrit api: {}", e);
        }
        return null;
    }
}
