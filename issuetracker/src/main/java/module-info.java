/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
module org.openjdk.skara.issuetracker {
    requires org.openjdk.skara.vcs;
    requires org.openjdk.skara.census;
    requires org.openjdk.skara.json;
    requires org.openjdk.skara.ini;
    requires org.openjdk.skara.process;
    requires org.openjdk.skara.email;
    requires org.openjdk.skara.network;
    requires transitive org.openjdk.skara.host;
    requires java.net.http;
    requires java.logging;

    exports org.openjdk.skara.issuetracker;
    exports org.openjdk.skara.issuetracker.jira;

    uses org.openjdk.skara.issuetracker.IssueTrackerFactory;

    provides org.openjdk.skara.issuetracker.IssueTrackerFactory with org.openjdk.skara.issuetracker.jira.JiraIssueTrackerFactory;
}
