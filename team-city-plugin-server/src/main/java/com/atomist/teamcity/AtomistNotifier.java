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
import java.util.Set;

public class AtomistNotifier extends NotificatorAdapter {

    private static Logger LOG = Logger.getInstance(AtomistNotifier.class.getName());

    public static String ATOMIST_TEAM_ID = "ATOMIST_TEAM_ID";
    public static String TEAM_CITY_BASE_URL = "TEAM_CITY_BASE_URL";

    public static String NOTIFIER_TYPE = "atomist-notifier";
    public static String ATOMIST_BASE_URL = "https://webhook.atomist.com/atomist/build/teams/";


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
        sendAtomistWebhook(build, users, BuildReport.STATUS_STARTED);
    }

    @Override
    public void notifyBuildFailed(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, BuildReport.STATUS_FAILURE);
    }

    @Override
    public void notifyBuildFailedToStart(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, BuildReport.STATUS_ERROR);
    }

    @Override
    public void notifyBuildSuccessful(@NotNull SRunningBuild build, @NotNull Set<SUser> users) {
        sendAtomistWebhook(build, users, BuildReport.STATUS_SUCCESS);
    }

    private void sendAtomistWebhook(@NotNull SRunningBuild build, @NotNull Set<SUser> users,
                                    String status) {
        users.forEach((user) -> {
            say("----------------- ATOMIST SAYS --------------------");
            say("Hello Notificator World, specifically " + user.getUsername());

            // gather settings from where the user activated the notification. To change these in TC:
            // click on my name; click Notification Rules on the left nav;
            // click Atomist underneath the Notification Rules heading.
            String teamId = user.getPropertyValue(new NotificatorPropertyKey(NOTIFIER_TYPE, ATOMIST_TEAM_ID));
            String baseUrl = user.getPropertyValue(new NotificatorPropertyKey(NOTIFIER_TYPE, TEAM_CITY_BASE_URL));
            say("Team ID:" + teamId);
            say("Base URL:" + baseUrl);

            // Gather data out of the build.
            int buildNumber = Integer.parseInt(build.getBuildNumber());

            build.getTriggeredBy().getParameters().forEach((k, v) -> say("triggering parameter: " + k + "=" + v));

            String buildUrl = constructBuildUrl(baseUrl, build);

            List<BuildRevision> revisions = build.getRevisions();
            revisions.forEach((revision) -> {
                say("-------------- Sending for revision: " + revision.getRevisionDisplayName());
                printStuffAboutTheRevision(revision);

                String teamCityBranchName = getFullBranch(build, revision.getRoot());
                say("The full branch is: " + teamCityBranchName);
                String buildTrigger = isPullRequest(teamCityBranchName) ? BuildReport.TYPE_PULL_REQUEST : BuildReport.TYPE_PUSH;
                Integer prNumber = buildTrigger.equals(BuildReport.TYPE_PULL_REQUEST) ? parsePullRequestNumber(teamCityBranchName) : null;
                String branch = buildTrigger.equals(BuildReport.TYPE_PUSH) ? stripBranchPrefixes(teamCityBranchName) : null;
                String sha = revision.getRevision();
                String buildName = build.getBuildTypeExternalId();
                String buildId = "" + build.getBuildId();
                String branchDisplayName = build.getBranch() == null ? null : build.getBranch().getDisplayName();

                String comment = build.getBuildComment() == null ? null : build.getBuildComment().getComment();
                say("build comment: " + comment);


                // Put it all together

                GenericBuildRepository scm = GenericBuildRepository.fromUrl(getRepoUrl(revision.getRoot()));
                ExtraData extraData = new ExtraData(comment, branchDisplayName, status == BuildReport.STATUS_ERROR);
                BuildReport buildPayload = new BuildReport(buildId, buildName, buildNumber, buildTrigger,
                        prNumber, branch, buildUrl, status, sha, scm, extraData);

                // serialize
                Gson gson = new Gson();
                String jsonPayload = gson.toJson(buildPayload);
                say("Sending to atomist: " + jsonPayload);

                // transmit
                String atomistUrl = ATOMIST_BASE_URL + teamId;
                say("url: " + atomistUrl);
                HttpPost httpPost = new HttpPost(atomistUrl);

                StringEntity entity = null;
                try {
                    entity = new StringEntity(jsonPayload);
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException(e);
                }
                httpPost.setEntity(entity);
                httpPost.setHeader("Accept", "application/json");
                httpPost.setHeader("Content-type", "application/json");


                try (CloseableHttpClient client = HttpClients.createMinimal()) { // should this be class-level? or static?
                    withFeebleRetry(() -> {
                        try {
                            CloseableHttpResponse response = client.execute(httpPost);
                            say(EntityUtils.toString(response.getEntity()));
                            response.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        });
    }

    private void withFeebleRetry(Runnable f) {
        try {
            f.run();
        } catch (Exception e) {
            say("oh look a nasty exception: " + e.getMessage());
            sleepForOneSecond();
            f.run(); // don't catch this time, let it go
        }
    }

    private void sleepForOneSecond() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e1) {
            throw new RuntimeException(e1);
        }
    }

    private void printStuffAboutTheRevision(BuildRevision r) {
        say("revision display name: " + r.getRevisionDisplayName());
        say("revision: " + r.getRevision());
        say("revision repository version display version: " + r.getRepositoryVersion().getDisplayVersion());
        say("revision repository version vcs branch: " + r.getRepositoryVersion().getVcsBranch());
        say("revision repository version version: " + r.getRepositoryVersion().getVersion());

        say("revision root vcs name: " + r.getRoot().getVcsName());
        say("revision root vcs display name: " + r.getRoot().getVcsDisplayName());
        say("revision root description: " + r.getRoot().getDescription());
        say("revision root properties: ");
        r.getRoot().getProperties().forEach((k, v) -> say(k + " = " + v));

        say("revision root parent external id: " + r.getRoot().getParent().getExternalId());
        say("revision root parent properties: ");
        r.getRoot().getParent().getProperties().forEach((k, v) -> say(k + " = " + v));

        say("revision entry signature: " + r.getEntry().getSignature());
        say("revision entry display name: " + r.getEntry().getDisplayName());
        say("revision entry checkout rules spec: " + r.getEntry().getCheckoutRulesSpecification());
        say("entry properties: ");
        r.getEntry().getProperties().forEach((k, v) -> say(k + " = " + v));

    }

    private String constructBuildUrl(String baseUrl, SBuild build) {
        long buildId = build.getBuildId();
        say("extended full name: " + build.getBuildType().getExtendedFullName());
        say("full name: " + build.getBuildType().getFullName());
        say("extended name: " + build.getBuildType().getExtendedName());
        say("build type ID: " + build.getBuildTypeId());
        say("build type name: " + build.getBuildTypeName());
        say("build full name: " + build.getFullName());
        say("build description: " + build.getBuildDescription());
        say("build branch name: " + (build.getBranch() == null ? "doh branch is null" : build.getBranch().getName()));
        say("build branch display name: " + (build.getBranch() == null ? "doh branch is null" : build.getBranch().getDisplayName()));
        say("build agent name: " + build.getAgentName());

        String buildType = build.getBuildTypeExternalId(); // Finally found it!

        return baseUrl + "/viewLog.html?buildId=" + buildId + "&buildTypeId=" + buildType + "&tab=buildLog";
    }

    /*
     * Guess the branch name. Might be like /refs/heads/master or /refs/pull/25/merge or might be shorter.
     */
    private String getFullBranch(SBuild build, VcsRootInstance vcsRoot) {
        say("The vcsRoot branch is: " + vcsRoot.getProperty("branch"));
        say("the build branch is: " + (build.getBranch() == null ? " null" : build.getBranch().getName()));

        say("VCS Root properties:");
        vcsRoot.getProperties().forEach((k, v) -> say(k + "=" + v));

        if (build.getBranch() == null) {
            return vcsRoot.getProperty("branch", "master");
        }

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
        if (fullBranch == null) {
            say("I could not find the full branch at all");
            return "mystery-branch";
        }
        return fullBranch
                .replaceFirst("^refs/heads/", "")
                .replaceFirst("^refs/pull/", "");
    }

    private boolean isPullRequest(String fullBranch) {
        if (fullBranch == null) {
            return false;
        }
        return fullBranch.startsWith("refs/pull/") || fullBranch.endsWith("/merge");
    }

    private Integer parsePullRequestNumber(String fullBranch) {
        if (fullBranch == null) {
            return null;
        }
        String withoutPrefix = stripBranchPrefixes(fullBranch);
        String numberPart = withoutPrefix.split("/")[0];
        try {
            return Integer.parseInt(numberPart);
        } catch (NumberFormatException nfe) {
            say("ERROR: Could not parse PR number from " + fullBranch + " because <" + numberPart + "> is not an int");
            return 0;
        }
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


    static class GenericBuildRepository {
        String owner_name;
        String name;

        GenericBuildRepository(String owner_name, String name) {
            this.owner_name = owner_name;
            this.name = name;
        }

        static GenericBuildRepository fromUrl(String url) {
            String[] components = url.split("/");
            String repoName = components[components.length - 1].replace(".git", "");
            String ownerName = components[components.length - 2];
            return new GenericBuildRepository(ownerName, repoName);
        }

    }

    static class ExtraData {
        String comment;
        String branchDisplayName;
        boolean failedToStart;

        ExtraData(String comment, String branchDisplayName, boolean failedToStart) {
            this.comment = comment;
            this.branchDisplayName = branchDisplayName;
            this.failedToStart = failedToStart;
        }

    }

    static class BuildReport {
        private static String TYPE_PULL_REQUEST = "pull_request";
        private static String TYPE_PUSH = "push";
        private static String TYPE_TAG = "tag";
        private static String TYPE_MANUAL = "manual";
        private static String TYPE_CRON = "cron";

        private static String STATUS_STARTED = "started";
        private static String STATUS_FAILURE = "failed";
        private static String STATUS_ERROR = "error";
        private static String STATUS_SUCCESS = "passed";
        private static String STATUS_CANCELED = "canceled";

        BuildReport(String buildId,
                    String name,
                    int number,
                    String type,
                    Integer pull_request_number, // if type = pull request
                    String branch, // if type = push
                    String build_url,
                    String status,
                    String sha,
                    GenericBuildRepository repository,
                    ExtraData data) {
            if (type.equals(TYPE_PULL_REQUEST) && pull_request_number == null) {
                throw new RuntimeException("Pull request builds require a PR number");
            }

            if (type.equals(TYPE_PUSH) && branch == null) {
                throw new RuntimeException("Push builds require a branch");
            }
            this.id = buildId;
            this.number = number;
            this.name = name;
            this.pull_request_number = pull_request_number;
            this.build_url = build_url;
            this.status = status;
            this.repository = repository;
            this.commit = sha;
            this.type = type;
            this.branch = branch;
            this.data = data;
        }

        String id;
        String type;
        int number;
        String name;
        Integer pull_request_number;
        String build_url;
        String status;
        String commit;
        // choosing not to populate: tag
        String branch;
        String provider = "TeamCity";
        GenericBuildRepository repository;
        ExtraData data;
    }

}
