/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.notify.issue;

import org.openjdk.skara.bots.notify.*;
import org.openjdk.skara.email.EmailAddress;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.issuetracker.Issue;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.jbs.*;
import org.openjdk.skara.jcheck.JCheckConfiguration;
import org.openjdk.skara.json.JSON;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;

import static org.openjdk.skara.issuetracker.jira.JiraProject.RESOLVED_IN_BUILD;

class IssueNotifier implements Notifier, PullRequestListener, RepositoryListener {
    private final IssueProject issueProject;
    private final boolean reviewLink;
    private final URI reviewIcon;
    private final boolean commitLink;
    private final URI commitIcon;
    private final boolean setFixVersion;
    private final Map<String, String> fixVersions;
    private final Map<String, List<String>> altFixVersions;
    private final JbsBackport jbsBackport;
    private final boolean prOnly;
    private final boolean repoOnly;
    private final String buildName;
    private final HostedRepository censusRepository;
    private final String censusRef;
    private final String namespace;
    // If true, use the version found in .jcheck/conf in the HEAD revision instead of the
    // current commit when resolving fixVersion for a new commit.
    private final boolean useHeadVersion;
    // If set, use this repository for generating URLs to commits instead of the one
    // supplied. This can be used to have the bot act on a mirror of the original
    // repository but still generate links to the original. Only works for notifications
    // on repository, not pull requests.
    private final HostedRepository originalRepository;
    // Controls whether the notifier should try to resolve issues. Only valid when
    // pronly is true.
    private final boolean resolve;

    // A set of version opt strings that may be part of fixVersion in issues, but that
    // do not need to be part of a tag to be considered a match.
    private final Set<String> tagIgnoreOpt;

    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.notify");

    private static final String pullRequestTip = "A pull request was submitted for review.";

    IssueNotifier(IssueProject issueProject, boolean reviewLink, URI reviewIcon, boolean commitLink, URI commitIcon,
                  boolean setFixVersion, Map<String, String> fixVersions, Map<String, List<String>> altFixVersions,
                  JbsBackport jbsBackport, boolean prOnly, boolean repoOnly, String buildName,
                  HostedRepository censusRepository, String censusRef, String namespace, boolean useHeadVersion,
                  HostedRepository originalRepository, boolean resolve, Set<String> tagIgnoreOpt) {
        this.issueProject = issueProject;
        this.reviewLink = reviewLink;
        this.reviewIcon = reviewIcon;
        this.commitLink = commitLink;
        this.commitIcon = commitIcon;
        this.setFixVersion = setFixVersion;
        this.fixVersions = fixVersions;
        this.altFixVersions = altFixVersions;
        this.jbsBackport = jbsBackport;
        this.prOnly = prOnly;
        this.repoOnly = repoOnly;
        this.buildName = buildName;
        this.censusRepository = censusRepository;
        this.censusRef = censusRef;
        this.namespace = namespace;
        this.useHeadVersion = useHeadVersion;
        this.originalRepository = originalRepository;
        this.resolve = resolve;
        this.tagIgnoreOpt = tagIgnoreOpt;
    }

    static IssueNotifierBuilder newBuilder() {
        return new IssueNotifierBuilder();
    }

    private Optional<String> findCensusUser(String user, Path scratchPath) {
        if (censusRepository == null) {
            return Optional.empty();
        }
        var censusInstance = CensusInstance.create(censusRepository, censusRef, scratchPath, namespace);
        var ns = censusInstance.namespace();
        for (var entry : ns.entries()) {
            if (entry.getValue().username().equals(user)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    private Optional<String> findIssueUsername(Commit commit, Path scratchPath) {
        var authorEmail = EmailAddress.from(commit.author().email());
        if (authorEmail.domain().equals(namespace)) {
            return Optional.of(authorEmail.localPart());
        } else {
            var user = findCensusUser(authorEmail.localPart(), scratchPath);
            if (user.isPresent()) {
                return user;
            }
        }

        var committerEmail = EmailAddress.from(commit.committer().email());
        if (committerEmail.domain().equals("openjdk.org")) {
            return Optional.of(committerEmail.localPart());
        } else {
            var user = findCensusUser(committerEmail.localPart(), scratchPath);
            if (user.isPresent()) {
                return user;
            }

            log.warning("Cannot determine issue tracker user name from committer email: " + committerEmail);
            return Optional.empty();
        }
    }

    @Override
    public void attachTo(Emitter e) {
        if (!repoOnly) {
            e.registerPullRequestListener(this);
        }
        if (!prOnly || buildName != null) {
            e.registerRepositoryListener(this);
        }
    }

    @Override
    public void onIntegratedPullRequest(PullRequest pr, Path scratchPath, Hash hash)  {
        var repository = pr.repository();
        var commit = repository.commit(hash).orElseThrow(() ->
                new IllegalStateException("Integrated commit " + hash +
                                          " not present in repository " + repository.webUrl())
        );
        var commitMessage = CommitMessageParsers.v1.parse(commit);
        for (var commitIssue : commitMessage.issues()) {
            var optionalIssue = issueProject.issue(commitIssue.shortId());
            if (optionalIssue.isEmpty()) {
                log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                        + " - issue not found in issue project");
                continue;
            }
            var issue = optionalIssue.get();

            if (commitLink) {
                var linkBuilder = Link.create(repository.webUrl(hash), "Commit")
                                      .summary(repository.name() + "/" + hash.abbreviate());
                if (commitIcon != null) {
                    linkBuilder.iconTitle("Commit");
                    linkBuilder.iconUrl(commitIcon);
                }
                issue.addLink(linkBuilder.build());
            }

            // If prOnly is false, this is instead done when processing commits
            if (prOnly && resolve) {
                if (issue.state() == Issue.State.OPEN) {
                    issue.setState(Issue.State.RESOLVED);
                    if (issue.assignees().isEmpty()) {
                        var username = findIssueUsername(commit, scratchPath);
                        if (username.isPresent()) {
                            var assignee = issueProject.issueTracker().user(username.get());
                            assignee.ifPresent(hostUser -> issue.setAssignees(List.of(hostUser)));
                        }
                    }
                }
            }
        }
    }

    @Override
    public void onNewIssue(PullRequest pr, Path scratchPath, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " added unknown issue: " + issue.id());
            return;
        }

        if (reviewLink) {
            var linkBuilder = Link.create(pr.webUrl(), "Review")
                                  .summary(pr.repository().name() + "/" + pr.id());
            if (reviewIcon != null) {
                linkBuilder.iconTitle("Review");
                linkBuilder.iconUrl(reviewIcon);
            }

            realIssue.get().addLink(linkBuilder.build());
        }

        var alreadyPostedComment = realIssue.get().comments().stream()
                .filter(comment -> comment.author().equals(issueProject.issueTracker().currentUser()))
                .anyMatch(comment -> comment.body().contains(pullRequestTip) && comment.body().contains(pr.webUrl().toString()));
        if (!alreadyPostedComment) {
            realIssue.get().addComment(pullRequestToTextBrief(pr));
        }
    }

    @Override
    public void onRemovedIssue(PullRequest pr, Path scratchPath, org.openjdk.skara.vcs.openjdk.Issue issue) {
        var realIssue = issueProject.issue(issue.shortId());
        if (realIssue.isEmpty()) {
            log.warning("Pull request " + pr + " removed unknown issue: " + issue.id());
            return;
        }

        var link = Link.create(pr.webUrl(), "").build();
        realIssue.get().removeLink(link);

        var postedComment = realIssue.get().comments().stream()
                .filter(comment -> comment.author().equals(issueProject.issueTracker().currentUser()))
                .filter(comment -> comment.body().contains(pullRequestTip) && comment.body().contains(pr.webUrl().toString()))
                .findAny();
        postedComment.ifPresent(comment -> realIssue.get().removeComment(comment));
    }

    private String pullRequestToTextBrief(PullRequest pr) {
        var builder = new StringBuilder();
        builder.append(pullRequestTip).append("\n");
        builder.append("URL: ").append(pr.webUrl().toString()).append("\n");
        builder.append("Date: ").append(pr.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss +0000")));
        return builder.toString();
    }

    @Override
    public void onNewCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, Branch branch) {
        for (var commit : commits) {
            var linkRepository = originalRepository != null ? originalRepository : repository;
            var commitNotification = CommitFormatters.toTextBrief(linkRepository, commit);
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            var username = findIssueUsername(commit, scratchPath);

            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.shortId());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update issue " + commitIssue.id() + " with commit " + commit.hash().abbreviate()
                                       + " - issue not found in issue project");
                    continue;
                }

                var issue = optionalIssue.get();
                var mainIssue = Backports.findMainIssue(issue);
                if (mainIssue.isEmpty()) {
                    log.severe("Issue " + issue.id() + " is not the main issue - bot no corresponding main issue found");
                    continue;
                } else {
                    if (!mainIssue.get().id().equals(issue.id())) {
                        log.warning("Issue " + issue.id() + " is not the main issue - using " + mainIssue.get().id() + " instead");;
                        issue = mainIssue.get();
                    }
                }

                String requestedVersion = null;
                // The actual issue to be updated can change depending on the fix version
                if (setFixVersion) {
                    requestedVersion = getRequestedVersion(localRepository, commit, branch.name());
                    if (requestedVersion != null) {
                        var altFixedVersionIssue = findAltFixedVersionIssue(issue, branch);
                        if (altFixedVersionIssue.isPresent()) {
                            log.info("Found an already fixed backport " + altFixedVersionIssue.get().id() + " for " + issue.id()
                                    + " with fixVersion " + Backports.mainFixVersion(altFixedVersionIssue.get()).orElseThrow());
                            issue = altFixedVersionIssue.get();
                            // Do not update fixVersion
                            requestedVersion = null;
                        } else {
                            var fixVersion = JdkVersion.parse(requestedVersion).orElseThrow();
                            var existing = Backports.findIssue(issue, fixVersion);
                            if (existing.isEmpty()) {
                                issue = jbsBackport.createBackport(issue, requestedVersion, username.orElse(null));
                            } else {
                                issue = existing.get();
                            }
                        }
                    }
                }

                var existingComments = issue.comments();
                var hashUrl = linkRepository.webUrl(commit.hash()).toString();
                // We used to store URLs with just the abbreviated hash, so need to check for both
                var shortHashUrl = linkRepository.webUrl(new Hash(commit.hash().abbreviate())).toString();
                var alreadyPostedComment = existingComments.stream()
                        .filter(comment -> comment.author().equals(issueProject.issueTracker().currentUser()))
                        .anyMatch(comment -> comment.body().contains(hashUrl) || comment.body().contains(shortHashUrl));
                if (!alreadyPostedComment) {
                    issue.addComment(commitNotification);
                }
                if (issue.state() == Issue.State.OPEN) {
                    issue.setState(Issue.State.RESOLVED);
                    if (issue.assignees().isEmpty()) {
                        if (username.isPresent()) {
                            var assignee = issueProject.issueTracker().user(username.get());
                            if (assignee.isPresent()) {
                                issue.setAssignees(List.of(assignee.get()));
                            }
                        }
                    }
                }

                if (setFixVersion) {
                    if (requestedVersion != null) {
                        if (buildName != null) {
                            // Check if the build name should be updated
                            var oldBuild = issue.properties().getOrDefault(RESOLVED_IN_BUILD, JSON.of());
                            if (BuildCompare.shouldReplace(buildName, oldBuild.asString())) {
                                issue.setProperty(RESOLVED_IN_BUILD, JSON.of(buildName));
                            } else {
                                log.info("Not replacing build " + oldBuild.asString() + " with " + buildName + " for issue " + issue.id());
                            }
                        }
                        issue.setProperty("fixVersions", JSON.of(requestedVersion));
                    }
                }
            }
        }
    }

    private Optional<Issue> findAltFixedVersionIssue(Issue issue, Branch branch) {
        if (altFixVersions != null) {
            for (var altFixVersionString : altFixVersions.getOrDefault(branch.name(), List.of())) {
                var altFixVersion = JdkVersion.parse(altFixVersionString).orElseThrow();
                var altBackport = Backports.findIssue(issue, altFixVersion);
                if (altBackport.isPresent()) {
                    if (altBackport.get().isFixed()) {
                        return altBackport;
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void onNewOpenJDKTagCommits(HostedRepository repository, Repository localRepository, Path scratchPath, List<Commit> commits, OpenJDKTag tag, Tag.Annotated annotated) throws NonRetriableException {
        if (!setFixVersion) {
            return;
        }
        if (buildName == null) {
            return;
        }
        if (tag.buildNum().isEmpty()) {
            return;
        }

        // Determine which branch(es) this tag belongs to
        var tagBranches = new ArrayList<String>();
        try {
            for (var branch : repository.branches()) {
                if (PreIntegrations.isPreintegrationBranch(branch.name())) {
                    continue;
                }
                var hash = localRepository.resolve(tag.tag()).orElseThrow();
                if (localRepository.isAncestor(hash, branch.hash())) {
                    tagBranches.add(branch.name());
                }
            }
            if (tagBranches.isEmpty()) {
                throw new RuntimeException("Cannot find any branch containing the tag " + tag.tag().name());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (var commit : commits) {
            var commitMessage = CommitMessageParsers.v1.parse(commit);
            for (var commitIssue : commitMessage.issues()) {
                var optionalIssue = issueProject.issue(commitIssue.shortId());
                if (optionalIssue.isEmpty()) {
                    log.severe("Cannot update \"Resolved in Build\" for issue " + commitIssue.id()
                                       + " - issue not found in issue project");
                    continue;
                }
                // The actual issue to be updated can change depending on the fix version
                for (var tagBranch : tagBranches) {
                    var issue = optionalIssue.get();
                    String requestedVersion = getRequestedVersion(localRepository, commit, tagBranch);
                    if (requestedVersion == null) {
                        log.info("Cannot update \"Resolved In Build\" for issue: " + issue.id() + ", branch: "
                                + tagBranch + " - no fixVersion configured");
                        continue;
                    }
                    var fixVersion = JdkVersion.parse(requestedVersion).orElseThrow();
                    var existing = Backports.findIssue(issue, fixVersion);
                    if (existing.isEmpty()) {
                        log.info("Cannot update \"Resolved in Build\" for issue: " + issue.id() + ", branch: "
                                + tagBranch + " - no suitable backport found");
                        continue;
                    } else {
                        issue = existing.get();
                    }

                    // Check if the build number should be updated
                    var tagVersion = JdkVersion.parse(tag.version());
                    if (tagVersion.isPresent() && tagVersionMatchesFixVersion(fixVersion, tagVersion.get())) {
                        var oldBuild = issue.properties().getOrDefault(RESOLVED_IN_BUILD, JSON.of());
                        var newBuild = "b" + String.format("%02d", tag.buildNum().get());
                        if (BuildCompare.shouldReplace(newBuild, oldBuild.asString())) {
                            issue.setProperty(RESOLVED_IN_BUILD, JSON.of(newBuild));
                        } else {
                            log.info("Not replacing build " + oldBuild.asString() + " with " + newBuild + " for issue " + issue.id());
                        }
                    } else {
                        log.info("Not updating build in issue " + issue.id() + " with fixVersion " + fixVersion + " from tag " + tag);
                    }
                }
            }
        }
    }

    private boolean tagVersionMatchesFixVersion(JdkVersion fixVersion, JdkVersion tagVersion) {
        if (fixVersion.equals(tagVersion)) {
            return true;
        }
        // If the fix version has an opt string that should be ignored, compare just the version
        // component parts.
        if (fixVersion.opt().isPresent()) {
            if (tagIgnoreOpt.contains(fixVersion.opt().get())
                    && fixVersion.components().equals(tagVersion.components())) {
                return true;
            }
            // If the opt strings shouldn't be ignored, break early if they aren't matching
            if (!fixVersion.opt().equals(tagVersion.opt())) {
                return false;
            }
        }

        // The fixVersion may have a prefix in the first component that is not present
        // in the tagVersion. e.g. 'openjdk8u342' vs '8u342'
        var fixComponents = fixVersion.components();
        var tagComponents = tagVersion.components();
        // Check that the rest of the components are equal
        if (fixComponents.size() > 0 && fixComponents.size() == tagComponents.size()
                && fixComponents.subList(1, fixComponents.size()).equals(tagComponents.subList(1, tagComponents.size()))) {
            var fixFirst = fixComponents.get(0);
            var tagFirst = tagComponents.get(0);
            // Check if the first fixVersion component has a prefix consisting of only lower case letters
            return fixFirst.matches("[a-z]+" + tagFirst);
        }
        return false;
    }

    private String getRequestedVersion(Repository localRepository, Commit commit, String branch) {
        var requestedVersion = fixVersions != null ? fixVersions.getOrDefault(branch, null) : null;
        if (requestedVersion == null) {
            try {
                var hash = (useHeadVersion ? localRepository.resolve(branch).orElseThrow() : commit.hash());
                var conf = localRepository.lines(Path.of(".jcheck/conf"), hash);
                if (conf.isPresent()) {
                    var parsed = JCheckConfiguration.parse(conf.get());
                    var version = parsed.general().version();
                    requestedVersion = version.orElse(null);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return requestedVersion;
    }

    @Override
    public String name() {
        return "issue";
    }

    @Override
    public boolean idempotent() {
        return true;
    }
}
