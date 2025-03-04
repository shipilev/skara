/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.webrev;

import org.openjdk.skara.vcs.*;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.util.List;
import java.util.stream.Collectors;

class AddedFileView implements FileView {
    private final Patch patch;
    private final Path out;
    private final List<CommitMetadata> commits;
    private final MetadataFormatter formatter;
    private final List<String> newContent;
    private final byte[] binaryContent;
    private final Stats stats;

    public AddedFileView(ReadOnlyRepository repo, Hash base, Hash head, List<CommitMetadata> commits, MetadataFormatter formatter, Patch patch, Path out) throws IOException {
        this.patch = patch;
        this.out = out;
        this.commits = commits;
        this.formatter = formatter;
        var path = patch.target().path().get();
        var pathInRepo = repo.root().resolve(path);
        if (patch.isTextual()) {
            binaryContent = null;
            if (head == null) {
                List<String> lines = null;
                for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1)) {
                    try {
                        lines = Files.readAllLines(pathInRepo, charset);
                        break;
                    } catch (MalformedInputException e) {
                        continue;
                    }
                }
                if (lines == null) {
                    throw new IllegalStateException("Could not read " + pathInRepo + " as UTF-8 nor as ISO-8859-1");
                }
                newContent = lines;
            } else {
                newContent = repo.lines(path, head).orElseThrow(IllegalArgumentException::new);
            }
            stats = new Stats(patch.asTextualPatch().stats(), newContent.size());
        } else {
            newContent = null;
            if (head == null) {
                binaryContent = Files.readAllBytes(pathInRepo);
            } else {
                binaryContent = repo.show(path, head).orElseThrow(IllegalArgumentException::new);
            }
            stats = Stats.empty();
        }
    }

    @Override
    public Stats stats() {
        return stats;
    }


    @Override
    public void render(Writer w) throws IOException {
        w.write("<p>\n");
        w.write("  <code>\n");
        if (patch.isTextual()) {
            w.write("------ ------ ------ ------ --- ");

            var newView = new NewView(out, patch.target().path().get(), newContent);
            newView.render(w);

            var addedPatchView = new AddedPatchView(out, patch.target().path().get(), patch.asTextualPatch());
            addedPatchView.render(w);

            var rawView = new RawView(out, patch.target().path().get(), newContent);
            rawView.render(w);
        } else {
            w.write("------ ------ ------ ------ --- --- ");

            var addedPatchView = new AddedPatchView(out, patch.target().path().get(), patch.asBinaryPatch());
            addedPatchView.render(w);

            var rawView = new RawView(out, patch.target().path().get(), binaryContent);
            rawView.render(w);
        }
        w.write("  </code>\n");
        w.write("  <span class=\"file-added\">");
        w.write(patch.target().path().get().toString());
        w.write("</span>");

        if (patch.target().type().get().isVCSLink()) {
            w.write(" <i>(submodule)</i>\n");
        } else if (patch.isBinary()) {
            w.write(" <i>(binary)</i>\n");
        } else {
            w.write("\n");
        }

        w.write("<p>\n");

        if (patch.isTextual()) {
            w.write("<blockquote>\n");
            if (!commits.isEmpty()) {
                w.write("  <pre>\n");
                w.write(commits.stream()
                        .map(formatter::format)
                        .collect(Collectors.joining("\n")));
                w.write("  </pre>\n");
            }
            w.write("  <span class=\"stat\">\n");
            w.write(stats.toString());
            w.write("  </span>");
            w.write("</blockquote>\n");
        }
    }
}
