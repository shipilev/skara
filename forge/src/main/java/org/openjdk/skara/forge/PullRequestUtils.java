/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.forge;

import org.openjdk.skara.census.*;
import org.openjdk.skara.vcs.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PullRequestUtils {
    private static Hash commitSquashed(Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException {
        return localRepo.commit(commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                                committer.name(), committer.email(), ZonedDateTime.now(), List.of(targetHash(localRepo)), localRepo.tree(finalHead));
    }

    private final static Pattern mergeSourcePattern = Pattern.compile("^Merge ([-/\\w:+]+)$");
    private final static Pattern hashSourcePattern = Pattern.compile("[0-9a-fA-F]{6,40}");

    private static Optional<Hash> fetchRef(Repository localRepo, URI uri, String ref) {
        // Just a plain name - is this a branch?
        try {
            var hash = localRepo.fetch(uri, "+" + ref + ":refs/heads/merge_source", false);
            return Optional.of(hash);
        } catch (IOException e) {
            // Ignored
        }

        // Perhaps it is an actual tag object - it cannot be fetched to a branch ref
        try {
            var hash = localRepo.fetch(uri, "+" + ref + ":refs/tags/merge_source_tag", false);
            return Optional.of(hash);
        } catch (IOException e) {
            // Ignored
        }

        return Optional.empty();
    }

    private static Hash fetchMergeSource(PullRequest pr, Repository localRepo) throws IOException, CommitFailure {
        var sourceMatcher = mergeSourcePattern.matcher(pr.title());
        if (!sourceMatcher.matches()) {
            throw new CommitFailure("Could not determine the source for this merge. A Merge PR title must be specified in the format: `" +
                                            mergeSourcePattern + "` to allow verification of the merge contents.");
        }
        var source = sourceMatcher.group(1);

        // A hash in the PRs local history can also be a valid source
        var hashSourceMatcher = hashSourcePattern.matcher(source);
        if (hashSourceMatcher.matches()) {
            var hash = localRepo.resolve(source);
            if (hash.isPresent()) {
                // A valid merge source hash cannot be an ancestor of the target branch (if so it would not need to be merged)
                var prTargetHash = PullRequestUtils.targetHash(localRepo);
                if (!localRepo.isAncestor(hash.get(), prTargetHash)) {
                    return hash.get();
                }
            }
        }

        String repoName;
        String ref;
        if (!source.contains(":")) {
            // Try to fetch the source as a name of a ref (branch or tag)
            var hash = fetchRef(localRepo, pr.repository().url(), source);
            if (hash.isPresent()) {
                return hash.get();
            }

            // Only valid option now is a repository - use default ref
            repoName = source;
            ref = Branch.defaultFor(VCS.GIT).name();
        } else {
            repoName = source.split(":", 2)[0];
            ref = source.split(":", 2)[1];
        }

        // If the repository name is unqualified we assume it is a sibling
        if (!repoName.contains("/")) {
            repoName = Path.of(pr.repository().name()).resolveSibling(repoName).toString();
        }

        // Validate the repository
        var sourceRepo = pr.repository().forge().repository(repoName);
        if (sourceRepo.isEmpty()) {
            throw new CommitFailure("Could not find project `" + repoName + "` - check that it is correct.");
        }

        var hash = fetchRef(localRepo, sourceRepo.get().url(), ref);
        if (hash.isPresent()) {
            return hash.get();
        } else {
            throw new CommitFailure("Could not find the branch or tag `" + ref + "` in the project `" + repoName + "` - check that it is correct.");
        }
    }

    private static Hash findSourceHash(PullRequest pr, Repository localRepo, List<CommitMetadata> commits) throws IOException, CommitFailure {
        if (commits.size() < 1) {
            throw new CommitFailure("A merge PR must contain at least one commit that is not already present in the target.");
        }

        // Fetch the source
        var sourceHead = fetchMergeSource(pr, localRepo);

        // Ensure that the source and the target are related
        localRepo.mergeBaseOptional(targetHash(localRepo), sourceHead)
                .orElseThrow(() -> new CommitFailure("The target and the source branches do not share common history - cannot merge them."));

        // Find the most recent commit from the merge source not present in the target
        var sourceHash = localRepo.mergeBase(pr.headHash(), sourceHead);
        var commitHashes = commits.stream()
                                  .map(CommitMetadata::hash)
                                  .collect(Collectors.toSet());
        if (!commitHashes.contains(sourceHash)) {
            throw new CommitFailure("A merge PR must contain at least one commit from the source branch that is not already present in the target.");
        }

        return sourceHash;
    }

    private static Hash commitMerge(PullRequest pr, Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException, CommitFailure {
        var commits = localRepo.commitMetadata(baseHash(pr, localRepo), finalHead);
        var sourceHash = findSourceHash(pr, localRepo, commits);
        var parents = List.of(localRepo.mergeBase(targetHash(localRepo), finalHead), sourceHash);

        return localRepo.commit(commitMessage, author.name(), author.email(), ZonedDateTime.now(),
                committer.name(), committer.email(), ZonedDateTime.now(), parents, localRepo.tree(finalHead));
    }

    public static Hash targetHash(Repository localRepo) throws IOException {
        return localRepo.resolve("prutils_targetref").orElseThrow(() -> new IllegalStateException("Must materialize PR first"));
    }

    public static Repository materialize(HostedRepositoryPool hostedRepositoryPool, PullRequest pr, Path path) throws IOException {
        var localRepo = hostedRepositoryPool.checkout(pr.repository(), pr.headHash().hex(), path);
        localRepo.fetch(pr.repository().url(), "+" + pr.targetRef() + ":prutils_targetref", false);
        return localRepo;
    }

    public static boolean isAncestorOfTarget(Repository localRepo, Hash hash) throws IOException {
        Optional<Hash> targetHash = localRepo.resolve("prutils_targetref");
        return localRepo.isAncestor(hash, targetHash.orElseThrow());
    }

    public static boolean isMerge(PullRequest pr) {
        return pr.title().startsWith("Merge");
    }

    public static Hash createCommit(PullRequest pr, Repository localRepo, Hash finalHead, Author author, Author committer, String commitMessage) throws IOException, CommitFailure {
        Hash commit;
        if (!isMerge(pr)) {
            commit = commitSquashed(localRepo, finalHead, author, committer, commitMessage);
        } else {
            commit = commitMerge(pr, localRepo, finalHead, author, committer, commitMessage);
        }
        localRepo.checkout(commit, true);
        return commit;
    }

    public static Hash baseHash(PullRequest pr, Repository localRepo) throws IOException {
        return localRepo.mergeBase(targetHash(localRepo), pr.headHash());
    }

    public static Set<Path> changedFiles(PullRequest pr, Repository localRepo) throws IOException {
        var ret = new HashSet<Path>();
        var changes = localRepo.diff(baseHash(pr, localRepo), pr.headHash());
        for (var patch : changes.patches()) {
            if (patch.status().isDeleted() || patch.status().isRenamed()) {
                patch.source().path().ifPresent(ret::add);
            }
            if (!patch.status().isDeleted()) {
                patch.target().path().ifPresent(ret::add);
            }
        }
        return ret;
    }

    public static Set<String> reviewerNames(List<Review> reviews, Namespace namespace) {
        return reviews.stream()
                      .map(review -> namespace.get(review.reviewer().id()))
                      .filter(Objects::nonNull)
                      .map(Contributor::username)
                      .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static boolean containsForeignMerge(PullRequest pr, Repository localRepo) throws IOException {
        var baseHash = baseHash(pr, localRepo);
        var commits = localRepo.commitMetadata(baseHash, pr.headHash());
        var mergeParents = commits.stream()
                                  .filter(CommitMetadata::isMerge)
                                  .flatMap(commit -> commit.parents().stream().skip(1))
                                  .collect(Collectors.toList());
        for (var mergeParent : mergeParents) {
            var mergeBase = localRepo.mergeBaseOptional(targetHash(localRepo), mergeParent);
            if (mergeBase.isEmpty() || !mergeBase.get().equals(mergeParent)) {
                return true;
            }
        }
        return false;
    }
}
