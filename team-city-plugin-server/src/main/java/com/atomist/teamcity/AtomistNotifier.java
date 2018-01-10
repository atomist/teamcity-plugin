package com.atomist.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildRevision;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.UserPropertyInfo;
import jetbrains.buildServer.users.NotificatorPropertyKey;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.vcs.VcsRootInstance;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AtomistNotifier extends NotificatorAdapter {

    private static Logger LOG = Logger.getInstance(AtomistNotifier.class.getName());

    public static String ATOMIST_TEAM_ID = "ATOMIST_TEAM_ID";
    public static String TEAM_CITY_BASE_URL = "TEAM_CITY_BASE_URL";

    public static String NOTIFIER_TYPE = "atomist-notifier";
    public static String ATOMIST_BASE_URL = "https://webhook.atomist.com/atomist/jenkins/teams/";

    private static String PHASE_STARTED = "STARTED";
    private static String PHASE_FINALIZED = "FINALIZED";

    private static String STATUS_STARTED = "STARTED";
    private static String STATUS_FAILURE = "FAILURE";
    private static String STATUS_SUCCESS = "SUCCESS";

    public AtomistNotifier(NotificatorRegistry notificatorRegistry) throws IOException {
        ArrayList<UserPropertyInfo> userProps = new ArrayList<>();
        userProps.add(new UserPropertyInfo(ATOMIST_TEAM_ID, "Atomist Team ID"));
        userProps.add(new UserPropertyInfo(TEAM_CITY_BASE_URL, "Base URL of this TeamCity instance"));
        notificatorRegistry.register(this, userProps);
    }

    @NotNull
    @Override
    public String getNotificatorType() {
        return NOTIFIER_TYPE;
    }

    @Override
    public void notifyBuildStarted(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, PHASE_STARTED, STATUS_STARTED);
    }

    @Override
    public void notifyBuildFailed(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, PHASE_FINALIZED, STATUS_FAILURE);
    }

    @Override
    public void notifyBuildFailedToStart(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        // TODO: distinguish this from failure somehow
        sendAtomistWebhook(build, users, PHASE_FINALIZED, STATUS_FAILURE);
    }

    @Override
    public void notifyBuildSuccessful(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, PHASE_FINALIZED, STATUS_SUCCESS);
    }

    private void sendAtomistWebhook(@NotNull SRunningBuild build, @NotNull Set<SUser> users,
                                    String phase, String status) {
        users.forEach((user) -> {
            say("Hello Notificator World, specifically " + user.getUsername());
            CloseableHttpClient client = HttpClients.createDefault();

            String teamId = user.getPropertyValue(new NotificatorPropertyKey(NOTIFIER_TYPE, ATOMIST_TEAM_ID));
            String baseUrl = user.getPropertyValue(new NotificatorPropertyKey(NOTIFIER_TYPE, TEAM_CITY_BASE_URL));
            say("Team ID:" + teamId);
            say("Base URL:" + baseUrl);
            String atomistUrl = ATOMIST_BASE_URL + teamId;
            HttpPost httpPost = new HttpPost(atomistUrl);


            long duration = build.getDuration();
            String buildNumber = build.getBuildNumber();
            long buildId = build.getBuildId();
            String buildType = build.getBuildType().getBuildTypeId();

            String buildUrl = baseUrl + "/viewLog.html?buildId=" + buildId + "&buildTypeId=" + buildType + "&tab=buildLog";

            // TODO: use JSON encoder

            List<BuildRevision> revisions = build.getRevisions();
            if (revisions.size() != 1) {
                say("Skipping: Expected 1 revision, saw " + revisions.size() + " on " + buildUrl);
                return;
            }
            BuildRevision revision = revisions.get(0);

            String branch = stripBranchPrefixes(getFullBranch(build, revision.getRoot(), buildUrl));

            say("The name of the VCS root is: " + revision.getRoot().getName());
            say("The display name of the VCS root is: " + revision.getRoot().getVcsDisplayName());
            String gitUrl = getRepoUrl(revision.getRoot());
            String sha = revision.getRevision();

            String scm = "{\"url\": \"" + gitUrl + "\", \"branch\": \"" + branch + "\", \"commit\": \"" + sha + "\"}";


            String payload = "{\"name\": \"" + branch +
                    "\", \"duration\": " + duration +
                    ", \"build\": {\"number\": \"" + buildNumber +
                    "\", \"phase\": \"" + phase +
                    "\", \"status\": \"" + status +
                    "\", \"full_url\": \"" + buildUrl + "\", \"scm\": " + scm + "}}";
            say("Sending to atomist: " + payload);

            //  {"name": "jellyfish", "duration": 0,
            // "build": {"number": "37", "phase": "STARTED", "status": "STARTED",
            //     "full_url": "http://localhost:8111/viewLog.html?buildId=46&buildTypeId=bt1&tab=buildLog",
            // "scm": {"url": "https://github.com/satellite-of-love/carrot", "branch": "jellyfish",
            // "commit": "f05c0f8843c661e66bac9a3f8a07b14ec70d10f8"}}}

            try {
                StringEntity entity = new StringEntity(payload);
                httpPost.setEntity(entity);
                httpPost.setHeader("Accept", "application/json");
                httpPost.setHeader("Content-type", "application/json");

                CloseableHttpResponse response = client.execute(httpPost);
                say(EntityUtils.toString(response.getEntity()));
                client.close();
            } catch (UnsupportedEncodingException e) {
                System.err.println("Failed to encode json: " + payload);
                e.printStackTrace();
            } catch (IOException e) {
                System.out.println("IO exception when posting" + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /*
     * Guess the branch name. Might be like /refs/heads/master or /refs/pull/25/merge or might be shorter.
     */
    private String getFullBranch(SBuild build, VcsRootInstance vcsRoot, String buildUrl) {
        if (build.getBranch().isDefaultBranch()) {
            // example: https://github.com/satellite-of-love/carrot#refs/heads/master
            String[] splitOnPound = vcsRoot.getName().split("#");
            if (splitOnPound.length != 2) {
                say("Warning: unable to parse branch from " + vcsRoot.getName() + " on " + buildUrl);
                return "master"; // it's a fine guess
            } else {
                return splitOnPound[1];
            }
        } else {
            return build.getBranch().getName();
        }
    }

    private String stripBranchPrefixes(String fullBranch) {
        return fullBranch
                .replaceFirst("^refs/heads/", "")
                .replaceFirst("^refs/pull/", "");
    }

    private String getRepoUrl(VcsRootInstance vcsRoot) {
        return vcsRoot.getName().replaceFirst("#.*$", "");
    }

    private void say(String message) {
        LOG.info(message);
        System.out.println(message); // TODO: figure out how to log
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "Atomist";
    }

    public static final String PREFIX = "build.revisions.";

    // https://github.com/pwielgolaski/teamcity-revisions/blob/master/src/main/java/jetbrains/buildServer/revisions/RevisionsParametersProvider.java


    private void processRevision(VcsRootInstance root, BuildRevision revision, Map<String, String> result, boolean includeId) {
        String prefix = PREFIX + (includeId ? root.getName() + "." : "");
        String revisionText = revision != null ? revision.getRevision() : "???";
        result.put(prefix + "revision", revisionText);
        result.put(prefix + "short", isGitRepo(root) ? revisionText.substring(0, Math.min(revisionText.length(), 7)) : "N/A");
    }

    private boolean isGitRepo(VcsRootInstance root) {
        return root != null && "jetbrains.git".equalsIgnoreCase(root.getVcsName());
    }
}
