package io.jenkins.plugins.gerrit.builder;

import static hudson.model.Computer.threadPoolForRemoting;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.CheckForNull;

import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.export.ExportedBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.google.gerrit.extensions.restapi.RestApiException;

import hudson.ExtensionList;
import hudson.Plugin;
import hudson.model.CauseAction;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.StringParameterValue;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import hudson.util.SequentialExecutionQueue;
import io.jenkins.plugins.gerrit.builder.fetcher.Build;
import io.jenkins.plugins.gerrit.builder.fetcher.GerritChange;
import io.jenkins.plugins.gerrit.builder.fetcher.GerritChangeFetcher;
import io.jenkins.plugins.gerrit.builder.fetcher.SubmitGroup;
import io.jenkins.plugins.gerrit.builder.scm.GerritBuilderExtension;
import io.jenkins.plugins.gerrit.builder.webhook.GerritProjectEvent;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

/**
 * Main class of the plugin keeping track of all changes and all builds that are
 * ongoing It is notified when the webhook is fired.
 *
 */
@ExportedBean
public class PluginImpl extends Plugin {

    private static final Logger log = LoggerFactory.getLogger(PluginImpl.class);

    private transient Configuration config;
    private Map<SubmitGroup, SubmitGroupBuildStatus> submitGroupStatuses = new HashMap<SubmitGroup, SubmitGroupBuildStatus>();

    private final transient SequentialExecutionQueue queue = new SequentialExecutionQueue(threadPoolForRemoting);

    @Override
    public void start() throws Exception {
        log.info("----------------------------Starting plugin-------------------------------");
    }

    public Configuration getConfiguration() {
        if (config == null) {
            config = ExtensionList.lookupSingleton(Configuration.class);
        }
        return config;
    }

    private GerritChangeFetcher createFetcher() {
        String credentialsId = getConfiguration().getCredentialsId();
        StandardUsernamePasswordCredentials cred = ApiHelper.getCredentials(credentialsId);
        String url = getConfiguration().getGerritServerUrl();

        return ApiHelper.createFetcher(url, cred, null);
    }

    @Override
    public void postInitialize() throws Exception {
        scheduleRefresh();
    }

    @CheckForNull
    public static PluginImpl getInstance() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            return jenkins.getPlugin(PluginImpl.class);
        } else {
            log.error("Error, No Jenkins instance found");
            return null;
        }
    }

    public void webHookEvent(GerritProjectEvent projectEvent) {
        log.info("Got Webhook:" + projectEvent);
        // TODO Update open changes according to event. For now, just read them all
        scheduleRefresh();
    }

    private Set<GerritChange> fetchChanges(GerritChangeFetcher fetcher) {
        Instant start = Instant.now();
        Set<GerritChange> openChanges = Collections.emptySet();
        try {
            openChanges = fetcher.getOpenChanges();
            log.info("openChanges=" + openChanges.size());
        } catch (RestApiException e) {
            log.error("Could not fetch open changes", e);
        }
        Instant end = Instant.now();
        log.info("Took {} to fetch open changes", Duration.between(start, end));
        return openChanges;
    }

    /**
     * This method will calculate builds required for the open changes. It will
     * download a submit preview git bundle for each submit group in order to
     * determine this.
     * 
     * @param fetcher
     */
    private void scheduleBuilds(GerritChangeFetcher fetcher, Set<GerritChange> openChanges) {
        Instant start = Instant.now();
        // Group the open changes into submit groups
        try {
            AbstractMap<String, SubmitGroup> submitGroups = fetcher.getSubmitGroups(openChanges);
            log.info("submitGroups=" + submitGroups.size());
            List<JobInfo> allAvailableJenkinsJobs = findAllJobs();
            int buildCount = 0;

            for (SubmitGroup submitGroup : submitGroups.values()) {
                log.info("  Submit group: " + submitGroup.toString() + " with following " + submitGroup.size()
                        + " items:");
                for (GerritChange change : submitGroup) {
                    log.info("    Change:" + change.toDebugString());
                }
                log.info("    Builds:");
                Set<Build> builds = fetcher.getRequiredBuilds(submitGroup);
                for (Build build : builds) {
                    buildCount++;
                    triggerBuilds(allAvailableJenkinsJobs, submitGroup, build);
                }
            }
            log.info("Total {} builds", buildCount);
        } catch (RestApiException e) {
            log.error("Problem scheduling builds", e);
        } catch (IOException e) {
            log.error("Problem scheduling builds", e);
        }

        Instant end = Instant.now();
        log.info("Took {} to schedule builds", Duration.between(start, end));
        printSubmitGroupStatues();
    }

    private void printSubmitGroupStatues() {
        log.info("------------------------------------------------------------------");
        log.info("------------------------------------------------------------------");
        for (SubmitGroup submitGroup : submitGroupStatuses.keySet()) {
            log.info("--- submitGroup: ---", submitGroup);
            SubmitGroupBuildStatus status = submitGroupStatuses.get(submitGroup);
            log.info("submitGroupStatus: {}", status);
        }
        log.info("------------------------------------------------------------------");
        log.info("------------------------------------------------------------------");
    }

    private void triggerBuilds(List<JobInfo> allAvailableJenkinsJobs, SubmitGroup submitGroup, Build build) {
        ParametersAction parametersAction = getParametersAction(build);
        CauseAction causeAction = new CauseAction(new GerritBuilderCause(submitGroup, build));
        int jobsFound = 0;

        synchronized (submitGroupStatuses) {
            if (!submitGroupStatuses.containsKey(submitGroup)) {
                log.info("Creating new SubmitGroupBuildStatus for build {}", build);
                submitGroupStatuses.put(submitGroup, new SubmitGroupBuildStatus());
            }
            SubmitGroupBuildStatus submitGroupStatus = submitGroupStatuses.get(submitGroup);
            // Loop all available Jenkins jobs and find which ones we should trigger
            for (JobInfo jobInfo : allAvailableJenkinsJobs) {
                if (jobInfo.matchesProject(build.project)) {
                    jobsFound++;
                    if (!submitGroupStatus.containsBuild(build)) {
                        log.info("-------------------------------------------------------------");
                        log.info("Triggering build: {} for {}", jobInfo.job.getName(), build);
                        log.info("-------------------------------------------------------------");
                        jobInfo.job.scheduleBuild2(0, parametersAction, causeAction);
                        // TODO This means we only support one Jenkins project / Gerrit project
                        // in order to change this we must also have the jobInfo.job.getName() as a key
                        // in the submitGroupStatus
                        submitGroupStatus.onTriggered(build);
                        break;
                    } else {
                        log.info("-------------------------------------------------------------");
                        log.info("Build is already started, no need to start another one");
                        log.info("-------------------------------------------------------------");
                    }
                }
            }
        }
        if (jobsFound == 0) {
            log.warn("No build jobs found that can build this submit group");
        }
    }

    private ParametersAction getParametersAction(Build build) {
        ParameterValue params[] = { new StringParameterValue("GERRIT_PROJECT", build.project),
                new StringParameterValue("GERRIT_BRANCH", build.branch),
                new StringParameterValue("GERRIT_CHANGE_NUMBER", Integer.toString(build.getChangeNumber())),
                new StringParameterValue("GERRIT_PATCHSET_NUMBER", Integer.toString(build.getPatchSet())) };
        return new ParametersAction(params);
    }

    class JobInfo {
        public Set<URL> urls;
        public WorkflowJob job;

        public JobInfo(WorkflowJob job, GitSCM scm) {
            log.info("New JobInfo");
            this.job = job;
            this.urls = new HashSet<URL>();
            for (UserRemoteConfig cfg : scm.getUserRemoteConfigs()) {
                String urlString = cfg.getUrl().replaceFirst("\\.git$", "");
                try {
                    log.info("Adding new URL:{}", urlString);
                    urls.add(new URL(urlString));
                } catch (MalformedURLException e) {
                    log.info("Bad URL: {}", e);
                }
            }
        }

        public boolean matchesProject(String project) {
            boolean result = false;
            for (URL url : urls) {
                log.info("Checking project:{} against url:{}", project, url);
                if (url.getPath().equals(project) || (url.getPath()).equals("/a/" + project)) {
                    result = true;
                    break;
                }
            }
            return result;
        }
    }

    private List<JobInfo> findAllJobs() {
        // Currently limited to WorkflowJob's
        List<WorkflowJob> jobs = Jenkins.get().getAllItems(WorkflowJob.class);

        List<JobInfo> result = new ArrayList<JobInfo>();

        for (WorkflowJob job : jobs) {
            log.info("Checking job: {}");
            if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob)) {
                log.info("Skipping, not parameterized");
                continue;
            }
            Collection<? extends SCM> scms = job.getSCMs();
            if (scms.size() == 0) {
                // https://issues.jenkins.io/browse/JENKINS-45720
                log.info("No SCM found in this job, make sure you have completed an initial build");
            }
            for (SCM scm : scms) {
                if (scm instanceof GitSCM) {
                    GerritBuilderExtension extension = ((GitSCM) scm).getExtensions().get(GerritBuilderExtension.class);
                    if (extension != null) {
                        result.add(new JobInfo(job, (GitSCM) scm));
                    } else {
                        log.info("Job have no GerritBuilderExtension, skipping");
                    }
                } else {
                    log.info("No git SCM");
                }
            }
        }
        log.info("Found {} jobs that can be used to build", result.size());
        return result;
    }

    private final Runnable refreshRun = new Runnable() {
        public void run() {
            GerritChangeFetcher fetcher = createFetcher();
            if (fetcher != null) {
                Set<GerritChange> openChanges = fetchChanges(fetcher);
                scheduleBuilds(fetcher, openChanges);
            }
        }
    };

    private void scheduleRefresh() {
        log.info("Scheduling a refresh...");
        queue.execute(refreshRun);
    }

    enum BuildState {
        STARTED, COMPLETED
    };

    private void updateSubmitGroupBuildStatus(Run run, BuildState state) {
        GerritBuilderCause gerritBuilderCause = (GerritBuilderCause) run.getCause(GerritBuilderCause.class);
        SubmitGroupBuildStatus finishedSubmitGroupStatus = null;
        SubmitGroupBuildStatus startedSubmitGroupStatus = null;

        if (gerritBuilderCause != null) {
            SubmitGroup submitGroup = gerritBuilderCause.getSubmitGroup();
            synchronized (submitGroupStatuses) {
                // Only do "fast" things inside this synchronization block
                SubmitGroupBuildStatus submitGroupStatus = submitGroupStatuses.get(submitGroup);
                if (submitGroupStatus != null) {
                    Build build = gerritBuilderCause.getBuild();
                    if (state == BuildState.COMPLETED) {
                        if (run.getResult() == Result.SUCCESS) {
                            submitGroupStatus.onSuccess(build);
                        } else {
                            submitGroupStatus.onFailure(build);
                        }
                        if (submitGroupStatus.completed()) {
                            // Remove the SubmitGroup from our list in order to enable rebuild
                            submitGroupStatuses.remove(submitGroup);

                            finishedSubmitGroupStatus = submitGroupStatus;
                        }
                    } else {
                        startedSubmitGroupStatus = submitGroupStatus;
                        log.info("Build {} started", build);
                        submitGroupStatus.onStarted(build);
                        try {
                            run.setDescription(build.toString());
                        } catch (IOException e) {
                            log.info("Could not set any description");
                        }
                    }
                } else {
                    log.info("Build is old, cancel it!");
                    run.getExecutor().interrupt();
                }
            }

            if (finishedSubmitGroupStatus != null) {
                boolean success = finishedSubmitGroupStatus.success();
                String header;
                if (success) {
                    log.info("---------------------------------------");
                    log.info("SubmitGroup {} built successfully!", finishedSubmitGroupStatus);
                    log.info("---------------------------------------");
                    header = "Build successful";
                } else {
                    log.info("---------------------------------------");
                    log.info("SubmitGroup {} failed!", finishedSubmitGroupStatus);
                    log.info("---------------------------------------");
                    header = "Build failed";
                }

                String message = getMessage(header, submitGroup, finishedSubmitGroupStatus);
                submitReviewScore(submitGroup, message, true, success ? 1 : -1);
            }
            if (startedSubmitGroupStatus != null) {
                startedSubmitGroupStatus.addBuildURL(run.getAbsoluteUrl());
                if (startedSubmitGroupStatus.allBuildsStarted()) {
                    String message = getMessage("Build started", submitGroup, startedSubmitGroupStatus);
                    submitReviewScore(submitGroup, message, false, 0);
                }

            }
        } else {
            log.info("We don't recognize the build");
        }
    }

    private String getMessage(String header, SubmitGroup submitGroup, SubmitGroupBuildStatus submitGroupStatus) {
        StringBuffer message = new StringBuffer(header).append(" for submit group ");
        message.append(submitGroup.toString()).append(":\n");
        for (String url : submitGroupStatus.getBuildURLs()) {
            message.append(url).append("\n");
        }
        return message.toString();
    }

    private void submitReviewScore(SubmitGroup submitGroup, String message, boolean notify, int score) {
        GerritChangeFetcher fetcher = getFetcher();
        if (fetcher != null) {
            for (GerritChange change : submitGroup) {
                try {
                    fetcher.submitReviewScore(change._number, change.patchset, message.toString(), true, score);
                } catch (RestApiException e) {
                    log.error("Failed posting review score: {}", e);
                }
            }
        }
    }

    public void onBuildCompleted(Run run) {
        log.info("onBuildCompleted {}", run);
        updateSubmitGroupBuildStatus(run, BuildState.COMPLETED);
    }

    public void onBuildStarted(Run run) {
        log.info("onBuildStarted {}", run);
        updateSubmitGroupBuildStatus(run, BuildState.STARTED);
    }

    public GerritChangeFetcher getFetcher() {
        return createFetcher();
    }

    public void configChanged() {
        log.info("Config changed?");
        // TODO check if anything actually changed
        scheduleRefresh();
    }
}
