package io.jenkins.plugins.gerrit.builder.fetcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TransportBundleStream;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;

public class GerritChangeFetcher {
    private static final String GERRIT_META_SUFFIX = "/meta";
    private GerritApi gerritApi;
    private Path tmpPath;
    private PrintStream printStream;

    private static final Logger log = LoggerFactory.getLogger(GerritChangeFetcher.class);

    private void log(String line) {
        if (printStream != null) {
            printStream.println(line);
        } else {
            log.info(line);
        }
    }

    public GerritChangeFetcher(GerritApi gerritApi, PrintStream printStream) throws IOException {
        this.printStream = printStream;
        this.gerritApi = gerritApi;
        this.tmpPath = Files.createTempDirectory("bundles");
        log("Storing change bundles in temporary directory " + tmpPath);
    }

    private static Set<GerritChange> changesToSet(List<ChangeInfo> changes) {
        Set<GerritChange> setChanges = new HashSet<GerritChange>();
        for (ChangeInfo change : changes) {
            setChanges.add(new GerritChange(change));
        }
        return setChanges;
    }

    public Set<GerritChange> getOpenChanges() throws RestApiException {
        // Limit search to open changes and 1 week old, max 100
        List<ChangeInfo> changes = gerritApi.changes().query("status%3Aopen%20-age%3A1w").withLimit(100)
                .withOptions(ListChangesOption.CURRENT_REVISION, ListChangesOption.LABELS).get();
        return changesToSet(changes);
    }

    private SubmitGroup getSubmitGroup(GerritChange change) throws RestApiException {
        // TODO Use "o=NON_VISIBLE_CHANGES" and remove change if 403
        // this is not implemented in the library yet
        // EnumSet<ListChangesOption> listOptions =
        // EnumSet.of(ListChangesOption.CURRENT_REVISION, ListChangesOption.LABELS);
        // EnumSet<SubmittedTogetherOption> submittedTogetherOptions =
        // EnumSet.of(SubmittedTogetherOption.NON_VISIBLE_CHANGES);
        // SubmittedTogetherInfo togetherChanges =
        // gerritApi.changes().id(change.id).submittedTogether(listOptions,
        // submittedTogetherOptions);
        List<ChangeInfo> togetherChanges = gerritApi.changes().id(change.id).submittedTogether();
        // We need to use the query interface to get the labels and current revision for
        // now
        List<ChangeInfo> togetherChangesFixed = new ArrayList<ChangeInfo>();
        for (ChangeInfo changeInfo : togetherChanges) {
            List<ChangeInfo> changes = gerritApi.changes().query("change:" + changeInfo._number)
                    .withOptions(ListChangesOption.CURRENT_REVISION, ListChangesOption.LABELS).get();
            togetherChangesFixed.add(changes.get(0));
        }
        SubmitGroup submitGroup = new SubmitGroup(togetherChangesFixed);
        // Special case if no other changes are submitted together
        // we need to add the change it self as it is not included.
        if (submitGroup.isEmpty()) {
            submitGroup.add(change);
        }
        return submitGroup;
    }

    public void printSubmitGroup(SubmitGroup submitGroup) {
        log("Submit group: with following items:");
        for (GerritChange change : submitGroup) {
            log("Change:" + change.toString());
        }
    }

    /**
     * Get a list of submit groups, i.e. changes that should be tested as a group as
     * they will can be submitted together.
     *
     * @param openChanges
     *
     * @return An AbstractMap of SubmitGroup that contains changes that should be
     *         tested as a group
     * @throws RestApiException
     */
    public AbstractMap<String, SubmitGroup> getSubmitGroups(Set<GerritChange> openChanges) throws RestApiException {
        List<SubmitGroup> submitGroups = new ArrayList<SubmitGroup>();

        for (GerritChange change : openChanges) {
            submitGroups.add(getSubmitGroup(change));
        }

        // Go though list of set and remove changes already included in other sets
        // leaving the submitGroups as small as possible
        for (int i = 0; i < submitGroups.size() - 1; i++) {
            SubmitGroup currentSubmitGroup = submitGroups.get(i);
            for (int j = i + 1; j < submitGroups.size(); j++) {
                SubmitGroup followingSubmitGroup = submitGroups.get(j);
                if (followingSubmitGroup.containsAll(currentSubmitGroup)) {
                    for (GerritChange change : currentSubmitGroup) {
                        followingSubmitGroup.remove(change);
                    }
                } else if (currentSubmitGroup.containsAll(followingSubmitGroup)) {
                    for (GerritChange change : followingSubmitGroup) {
                        currentSubmitGroup.remove(change);
                    }
                }
            }
        }

        // Remove empty groups and groups where all changes are already tested
        submitGroups.removeIf(submitGroup -> submitGroup.isEmpty() || submitGroup.allTested());

        HashMap<String, SubmitGroup> result = new HashMap<String, SubmitGroup>();
        for (SubmitGroup submitGroup : submitGroups) {
            result.put(submitGroup.toString(), submitGroup);
        }
        return result;
    }

    public Set<Build> getRequiredBuilds(SubmitGroup submitGroup) throws IOException, RestApiException {
        // Get submit preview and detect all branches and projects that needs to be
        // built
        Set<Build> builds = new HashSet<Build>();

        BundleReader bundleReader = new BundleReader(submitGroup);
        Set<String> projects = bundleReader.getProjectNames();

        for (String project : projects) {
            Collection<GitRef> refs = findRefs(bundleReader.getInputStream(project));

            for (GitRef gitRef : refs) {
                // gitRef here is "refs/heads/<BRANCH>" we remove the "refs/heads/" part
                String branch = gitRef.name.split("refs/heads/")[1];
                builds.add(new Build(submitGroup, project, branch));
            }
        }
        bundleReader.close();
        return builds;
    }

    /**
     * Loop through a bundle file and give some information about it. Filter out
     * refs ending with /meta since they don't get built.
     *
     * @param bundle
     * @return A collection of branches
     * @throws IOException
     */
    protected static Collection<GitRef> findRefs(InputStream bundle) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(bundle));

        /* String gitVersion = */ in.readLine();

        String line = in.readLine();
        while (!line.equals("") && line != null && line.startsWith("-")) {
            // String requires = line.substring(1);
            line = in.readLine();
        }

        ArrayList<GitRef> branches = new ArrayList<GitRef>();
        while (!line.equals("") && line != null) {
            String sha1AndBranch[] = line.split(" ");
            String sha1 = sha1AndBranch[0];
            String branch = sha1AndBranch[1];
            if (!branch.endsWith(GERRIT_META_SUFFIX)) {
                branches.add(new GitRef(branch, sha1));
            }
            line = in.readLine();
        }
        return branches;
    }

    class BundleReader {
        private ZipFile zip;
        private Set<String> projectNames;
        private HashMap<String, InputStream> inputStreams;
        File zipFile;

        public BundleReader(int number, int patchSet) throws RestApiException, IOException {
            inputStreams = new HashMap<String, InputStream>();

            String fileName = "" + number + "-" + patchSet + ".zip";
            BinaryResult binary = gerritApi.changes().id(number).revision(patchSet).submitPreview("zip");
            zipFile = new File(tmpPath.toFile(), fileName);
            FileOutputStream fs = new FileOutputStream(zipFile);
            binary.writeTo(fs);

            readZipFile();
        }

        public BundleReader(SubmitGroup submitGroup) throws RestApiException, IOException {
            this(submitGroup.first()._number, submitGroup.first().patchset);
        }

        private void readZipFile() throws IOException {
            if (zip != null) {
                // Close the previous session which means all InputStreams are closed
                close();
            }
            projectNames = new HashSet<String>();
            zip = new ZipFile(zipFile);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String projectName = zipEntry.getName().split("\\.git")[0];
                projectNames.add(projectName);
                InputStream stream = zip.getInputStream(zipEntry);
                inputStreams.put(projectName, stream);
            }
            projectNames = Collections.unmodifiableSet(projectNames);
        }

        public void close() {
            try {
                zip.close(); // This will also close all InputStreams
            } catch (IOException e) {
                // Ignore
            }
            zipFile.delete();
        }

        public Set<String> getProjectNames() {
            return projectNames;
        }

        /**
         * Only one InputStream must be used at the same time
         * 
         * @param projectName
         * @return
         * @throws IOException
         */
        public InputStream getInputStream(String projectName) throws IOException {
            // An InputStream can only be read once, so remove it when used and reopen
            // the zip file again if needed.
            if (projectNames.contains(projectName)) {
                if (!inputStreams.containsKey(projectName)) {
                    // Project input stream was already used, read the zip file again
                    readZipFile();
                }
                return inputStreams.remove(projectName);
            } else {
                return null;
            }
        }
    }

    private void fetchFromBundle(Repository repo, InputStream bundle, String branch) throws URISyntaxException,
            NotSupportedException, org.eclipse.jgit.errors.TransportException, RefAlreadyExistsException,
            RefNotFoundException, InvalidRefNameException, CheckoutConflictException, GitAPIException {
        final RefSpec rs = new RefSpec("+" + Constants.R_HEADS + "*:" + Constants.R_HEADS + "*");
        final Set<RefSpec> refs = Collections.singleton(rs);
        final URIish uri = new URIish("in-memory://");

        TransportBundleStream trans = new TransportBundleStream(repo, uri, bundle);
        trans.fetch(NullProgressMonitor.INSTANCE, refs);
        trans.close();
    }

    private class SubmoduleInfo {
        public String branch;
        public String url;
        public String path;

        public SubmoduleInfo(Config gitModulesConfig, String submoduleName, File baseFile) {
            this.branch = gitModulesConfig.getString("submodule", submoduleName, "branch");
            this.path = gitModulesConfig.getString("submodule", submoduleName, "path");
            // We need to normalize the relative URL. I.e. convert "main/../submodule1" to
            // "submodule1"
            String url = gitModulesConfig.getString("submodule", submoduleName, "url");
            String urlPath = new File(baseFile, url).toPath().normalize().toString();
            this.url = urlPath;
        }
    }

    /**
     * Apply changes for a build to a project directory.
     *
     * The change bundle needed for this is downloaded and the applied to the
     * repository and all submodules. The correct version is then checked out.
     *
     * @param gitDir The Directory containing the project
     * @param build  The build configuration
     *
     * @throws IOException
     * @throws InvalidRemoteException
     * @throws TransportException
     * @throws GitAPIException
     * @throws URISyntaxException
     * @throws ConfigInvalidException
     * @throws RestApiException
     */
    public void prepareForBuild(File gitDir, Build build) throws IOException, InvalidRemoteException,
            TransportException, GitAPIException, URISyntaxException, ConfigInvalidException, RestApiException {
        prepareForBuild(gitDir, build.project, build.branch, build.getChangeNumber(), build.getPatchSet());
    }

    public void prepareForBuild(File gitDir, String project, String branch, int changeNumber, int patchset)
            throws IOException, InvalidRemoteException, TransportException, GitAPIException, URISyntaxException,
            ConfigInvalidException, RestApiException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repo = builder.readEnvironment() // scan environment GIT_* variables
                .setWorkTree(gitDir).setMustExist(true).readEnvironment().build();
        BundleReader bundleReader = new BundleReader(changeNumber, patchset);

        applySubmitGroup(repo, project, branch, bundleReader);
    }

    private void applySubmitGroup(Repository repo, String projectName, String branch, BundleReader bundleReader)
            throws IOException, InvalidRemoteException, TransportException, GitAPIException, URISyntaxException,
            ConfigInvalidException {

        Git git = new Git(repo);

        // Fetch the bundle if this project was part of the bundle zip
        if (bundleReader.getProjectNames().contains(projectName)) {
            fetchFromBundle(repo, bundleReader.getInputStream(projectName), branch);
            git.checkout().setName(branch).call();
            git.reset().setMode(ResetType.HARD).call();
        }

        // Figure out which branch and which bundle InputStream we should use for each
        // submodule in this repository by reading the .gitmodules file
        FileBasedConfig gitModules = new FileBasedConfig(new File(repo.getWorkTree(), ".gitmodules"), FS.DETECTED);
        gitModules.load();

        Set<String> submodules = gitModules.getSubsections("submodule");

        File baseFile = new File(projectName);

        HashMap<String, SubmoduleInfo> submoduleInfos = new HashMap<String, SubmoduleInfo>();
        for (String submodule : submodules) {
            SubmoduleInfo submoduleInfo = new SubmoduleInfo(gitModules, submodule, baseFile);
            submoduleInfos.put(submoduleInfo.path, submoduleInfo);
        }

        // Recursively update all submodules in this project
        SubmoduleWalk walk = SubmoduleWalk.forIndex(repo);
        while (walk.next()) {
            Repository submoduleRepo = walk.getRepository();
            if (submoduleRepo == null) {
                throw new ConfigInvalidException("null repo, add \"Advanced sub-modules behaviours\" "
                        + "and check \"Recursively update submodules\"");
            }
            String name = walk.getModuleName();
            SubmoduleInfo submoduleInfo = submoduleInfos.get(name);

            applySubmitGroup(submoduleRepo, submoduleInfo.url, submoduleInfo.branch, bundleReader);
            submoduleRepo.close();
        }
        // Do a final submodule update to make sure that all projects points
        // to correct SHA1. This takes care of the case where a submodule was dirty
        // but did not contain any changes in this bundle.
        git.submoduleUpdate().call();
        git.close();
    }

    public void submitReviewScore(int changeNumber, int patchset, String message, boolean notify, int score)
            throws RestApiException {
        ReviewInput reviewInput = new ReviewInput().message(message).label("Verified", score);
        if (notify) {
            reviewInput.notify = NotifyHandling.OWNER;
        } else {
            reviewInput.notify = NotifyHandling.NONE;
        }
        gerritApi.changes().id(changeNumber).revision(patchset).review(reviewInput);
    }
}
