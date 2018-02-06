package com.atomist.teamcity;

import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.notification.NotificatorAdapter;
import jetbrains.buildServer.notification.NotificatorRegistry;
import jetbrains.buildServer.serverSide.BuildRevision;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;
import jetbrains.buildServer.serverSide.UserPropertyInfo;
import jetbrains.buildServer.users.NotificatorPropertyKey;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.vcs.VcsException;
import jetbrains.buildServer.vcs.VcsRootInstance;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class AtomistNotifier extends NotificatorAdapter {

    private static Logger LOG = Logger.getInstance(AtomistNotifier.class.getName());

    public static String ATOMIST_TEAM_ID = "ATOMIST_TEAM_ID";
    public static String TEAM_CITY_BASE_URL = "TEAM_CITY_BASE_URL";

    public static String NOTIFIER_TYPE = "atomist-notifier";
    public static String ATOMIST_BASE_URL = "https://webhook.atomist.com/atomist/jenkins/teams/";


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

    @NotNull
    @Override
    public String getDisplayName() {
        return "Atomist";
    }

    @Override
    public void notifyBuildStarted(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, BuildReport.PHASE_STARTED, BuildReport.STATUS_STARTED);
    }

    @Override
    public void notifyBuildFailed(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, BuildReport.PHASE_FINALIZED, BuildReport.STATUS_FAILURE);
    }

    @Override
    public void notifyBuildFailedToStart(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        // TODO: distinguish this from failure somehow
        sendAtomistWebhook(build, users, BuildReport.PHASE_FINALIZED, BuildReport.STATUS_FAILURE);
    }

    @Override
    public void notifyBuildSuccessful(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, BuildReport.PHASE_FINALIZED, BuildReport.STATUS_SUCCESS);
    }

    private void sendAtomistWebhook(@NotNull SRunningBuild build, @NotNull Set<SUser> users,
                                    String phase, String status) {
        users.forEach((user) -> {
            say("Hello Notificator World, specifically " + user.getUsername());
            CloseableHttpClient client = HttpClients.createDefault();

            // gather settings from where the user activated the notification. To change these in TC:
            // click on my name; click Notification Rules on the left nav;
            // click Atomist underneath the Notification Rules heading.
            String teamId = user.getPropertyValue(new NotificatorPropertyKey(NOTIFIER_TYPE, ATOMIST_TEAM_ID));
            String baseUrl = user.getPropertyValue(new NotificatorPropertyKey(NOTIFIER_TYPE, TEAM_CITY_BASE_URL));
            say("Team ID:" + teamId);
            say("Base URL:" + baseUrl);

            // Gather data out of the build.
            long duration = build.getDuration();
            String buildNumber = build.getBuildNumber();

            String buildUrl = constructBuildUrl(baseUrl, build);

            List<BuildRevision> revisions = build.getRevisions();
            if (revisions.size() != 1) {
                say("Skipping: Expected 1 revision, saw " + revisions.size() + " on " + buildUrl);
                return;
            }
            BuildRevision revision = revisions.get(0);

            String teamCityBranchName = getFullBranch(build, revision.getRoot());
            String branch = stripBranchPrefixes(teamCityBranchName);
            String gitUrl = getRepoUrl(revision.getRoot());
            String sha = revision.getRevision();



            // Put it all together

            GitInfo scm = new GitInfo(gitUrl, branch, sha);
            BuildReport buildPayload = new BuildReport(buildNumber, phase, status, buildUrl, scm);
            AtomistWebhookPayload payload = new AtomistWebhookPayload(teamCityBranchName, duration, buildPayload);

            // serialize
            Gson gson = new Gson();
            String jsonPayload = gson.toJson(payload);
            say("Sending to atomist: " + jsonPayload);

            // transmit
            try {
                String atomistUrl = ATOMIST_BASE_URL + teamId;
                HttpPost httpPost = new HttpPost(atomistUrl);

                StringEntity entity = new StringEntity(jsonPayload);
                httpPost.setEntity(entity);
                httpPost.setHeader("Accept", "application/json");
                httpPost.setHeader("Content-type", "application/json");

                CloseableHttpResponse response = client.execute(httpPost);
                say(EntityUtils.toString(response.getEntity()));
                client.close();
            } catch (IOException e) {
                System.out.println("IO exception when posting" + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private String constructBuildUrl(String baseUrl, SBuild build) {
        long buildId = build.getBuildId();
//        say("extended full name: " + build.getBuildType().getExtendedFullName());
//        say("full name: " + build.getBuildType().getFullName());
//        say("extended name: " + build.getBuildType().getExtendedName());
//        say("build type ID " + build.getBuildTypeId());
//        say("build type name " + build.getBuildTypeName());
//        say("build full name " + build.getFullName());
//        say("build description" + build.getBuildDescription());
//        say("build branch name" + build.getBranch().getName());
//        say("build branch display name" + build.getBranch().getDisplayName());
//        say("build agent name" + build.getAgentName());

        String buildType = build.getBuildTypeExternalId(); // Finally found it!

        return baseUrl + "/viewLog.html?buildId=" + buildId + "&buildTypeId=" + buildType + "&tab=buildLog";
    }

    /*
     * Guess the branch name. Might be like /refs/heads/master or /refs/pull/25/merge or might be shorter.
     */
    private String getFullBranch(SBuild build, VcsRootInstance vcsRoot) {
//        say("The vcsRoot branch is: " + vcsRoot.getProperty("branch"));
//        say("the build branch is: " + build.getBranch().getName());

        vcsRoot.getProperties().forEach((k,v) -> say(k + "=" + v));
        if (build.getBranch().isDefaultBranch()) {
            if (vcsRoot.getProperty("branch") == null) {
                say("Warning: no branch property on vcsRoot " + vcsRoot.getName());
            }
            return vcsRoot.getProperty("branch", "master");
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
        if (vcsRoot.getProperty("url") == null) {
            say("Warning: no branch property on vcsRoot " + vcsRoot.getName());
        }
        return vcsRoot.getProperty("url");
    }

    private void say(String message) {
        LOG.info(message); // this doesn't work. I have not found the log4j incantation to make it work.
        System.out.println(message); // this appears in catalina.out in the TC server's log directory
    }


    static class GitInfo {
        GitInfo(String url, String branch, String commit) {
            this.url = url;
            this.branch = branch;
            this.commit = commit;
        }

        final String url;
        final String branch;
        final String commit;
    }

    static class BuildReport {
        private static String PHASE_STARTED = "STARTED";
        private static String PHASE_FINALIZED = "FINALIZED";

        private static String STATUS_STARTED = "STARTED";
        private static String STATUS_FAILURE = "FAILURE";
        private static String STATUS_SUCCESS = "SUCCESS";

        BuildReport(String number, String phase, String status, String full_url, GitInfo scm) {
            this.number = number;
            this.phase = phase;
            this.status = status;
            this.full_url = full_url;
            this.scm = scm;
        }

        String number;
        String phase;
        String status;
        String full_url;
        GitInfo scm;
    }

    static class AtomistWebhookPayload {

        AtomistWebhookPayload(String name, long duration, BuildReport build) {
            this.name = name;
            this.duration = duration;
            this.build = build;
        }

        String name;
        long duration;
        BuildReport build;
    }
}
