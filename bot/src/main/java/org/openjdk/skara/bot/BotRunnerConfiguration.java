/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.skara.bot;

import org.openjdk.skara.ci.ContinuousIntegration;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.Credential;
import org.openjdk.skara.issuetracker.*;
import org.openjdk.skara.json.JSONObject;
import org.openjdk.skara.network.URIBuilder;
import org.openjdk.skara.vcs.Branch;
import org.openjdk.skara.vcs.VCS;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpHandler;

public class BotRunnerConfiguration {
    private final Logger log;
    private final JSONObject config;
    private final Map<String, Forge> repositoryHosts;
    private final Map<String, IssueTracker> issueHosts;
    private final Map<String, ContinuousIntegration> continuousIntegrations;
    private final Map<String, HostedRepository> repositories;

    private BotRunnerConfiguration(JSONObject config, Path cwd) throws ConfigurationError {
        this.config = config;
        log = Logger.getLogger("org.openjdk.skara.bot");

        repositoryHosts = parseRepositoryHosts(config, cwd);
        issueHosts = parseIssueHosts(config, cwd);
        continuousIntegrations = parseContinuousIntegrations(config, cwd);
        repositories = parseRepositories(config);
    }

    private Map<String, Forge> parseRepositoryHosts(JSONObject config, Path cwd) throws ConfigurationError {
        Map<String, Forge> ret = new HashMap<>();

        if (!config.contains("forges")) {
            return ret;
        }

        for (var entry : config.get("forges").fields()) {
            if (entry.value().contains("gitlab")) {
                var gitlab = entry.value().get("gitlab");
                var uri = URIBuilder.base(gitlab.get("url").asString()).build();
                var pat = new Credential(gitlab.get("username").asString(), gitlab.get("pat").asString());
                ret.put(entry.name(), Forge.from("gitlab", uri, pat, gitlab.asObject()));
            } else if (entry.value().contains("github")) {
                var github = entry.value().get("github");
                URI uri;
                if (github.contains("url")) {
                    uri = URIBuilder.base(github.get("url").asString()).build();
                } else {
                    uri = URIBuilder.base("https://github.com/").build();
                }

                if (github.contains("app")) {
                    var keyFile = cwd.resolve(github.get("app").get("key").asString());
                    try {
                        var keyContents = Files.readString(keyFile, StandardCharsets.UTF_8);
                        var pat = new Credential(github.get("app").get("id").asString() + ";" +
                                                         github.get("app").get("installation").asString(),
                                                 keyContents);
                        ret.put(entry.name(), Forge.from("github", uri, pat, github.asObject()));
                    } catch (IOException e) {
                        throw new ConfigurationError("Cannot find key file: " + keyFile);
                    }
                } else if (github.contains("username")) {
                    var pat = new Credential(github.get("username").asString(), github.get("pat").asString());
                    ret.put(entry.name(), Forge.from("github", uri, pat, github.asObject()));
                } else {
                    ret.put(entry.name(), Forge.from("github", uri, github.asObject()));
                }
            } else {
                throw new ConfigurationError("Host " + entry.name());
            }
        }

        return ret;
    }

    private Map<String, IssueTracker> parseIssueHosts(JSONObject config, Path cwd) throws ConfigurationError {
        Map<String, IssueTracker> ret = new HashMap<>();

        if (!config.contains("issuetrackers")) {
            return ret;
        }

        for (var entry : config.get("issuetrackers").fields()) {
            if (entry.value().contains("jira")) {
                var jira = entry.value().get("jira");
                var uri = URIBuilder.base(jira.get("url").asString()).build();
                Credential credential = null;
                if (jira.contains("username")) {
                    credential = new Credential(jira.get("username").asString(), jira.get("password").asString());
                }
                ret.put(entry.name(), IssueTracker.from("jira", uri, credential, jira.asObject()));
            } else {
                throw new ConfigurationError("Host " + entry.name());
            }
        }

        return ret;
    }

    private Map<String, ContinuousIntegration> parseContinuousIntegrations(JSONObject config, Path cwd) throws ConfigurationError {
        Map<String, ContinuousIntegration> ret = new HashMap<>();

        if (!config.contains("ci")) {
            return ret;
        }

        for (var entry : config.get("ci").fields()) {
            var url = entry.value().get("url").asString();
            var ci = ContinuousIntegration.from(URI.create(url), entry.value().asObject());
            if (ci.isPresent()) {
                ret.put(entry.name(), ci.get());
            } else {
                throw new ConfigurationError("No continuous integration named with url: " + url);
            }
        }

        return ret;
    }

    private Map<String, HostedRepository> parseRepositories(JSONObject config) throws ConfigurationError {
        Map<String, HostedRepository> ret = new HashMap<>();

        if (!config.contains("repositories")) {
            return ret;
        }

        for (var entry : config.get("repositories").fields()) {
            var hostName = entry.value().get("host").asString();
            if (!repositoryHosts.containsKey(hostName)) {
                throw new ConfigurationError("Repository " + entry.name() + " uses undefined host '" + hostName + "'");
            }
            var host = repositoryHosts.get(hostName);
            var repo = host.repository(entry.value().get("repository").asString()).orElseThrow(() ->
                    new ConfigurationError("Repository " + entry.value().get("repository").asString() + " is not available at " + hostName)
            );
            ret.put(entry.name(), repo);
        }

        return ret;
    }

    private static class RepositoryEntry {
        HostedRepository repository;
        String ref;
    }

    private RepositoryEntry parseRepositoryEntry(String entry) throws ConfigurationError {
        var ret = new RepositoryEntry();
        var refSeparatorIndex = entry.indexOf(':');
        if (refSeparatorIndex >= 0) {
            ret.ref = entry.substring(refSeparatorIndex + 1);
            entry = entry.substring(0, refSeparatorIndex);
        }
        var hostSeparatorIndex = entry.indexOf('/');
        if (hostSeparatorIndex >= 0) {
            var hostName = entry.substring(0, hostSeparatorIndex);
            var host = repositoryHosts.get(hostName);
            if (!repositoryHosts.containsKey(hostName)) {
                throw new ConfigurationError("Repository entry " + entry + " uses undefined host '" + hostName + "'");
            }
            var repositoryName = entry.substring(hostSeparatorIndex + 1);
            ret.repository = host.repository(repositoryName).orElseThrow(() ->
                    new ConfigurationError("Repository " + repositoryName + " is not available at " + hostName)
            );
        } else {
            if (!repositories.containsKey(entry)) {
                throw new ConfigurationError("Repository " + entry + " is not defined!");
            }
            ret.repository = repositories.get(entry);
        }

        if (ret.ref == null) {
            ret.ref = Branch.defaultFor(ret.repository.repositoryType()).name();
        }

        return ret;
    }

    private IssueProject parseIssueProjectEntry(String entry) throws ConfigurationError {
        var hostSeparatorIndex = entry.indexOf('/');
        if (hostSeparatorIndex >= 0) {
            var hostName = entry.substring(0, hostSeparatorIndex);
            var host = issueHosts.get(hostName);
            if (!issueHosts.containsKey(hostName)) {
                throw new ConfigurationError("Issue project entry " + entry + " uses undefined host '" + hostName + "'");
            }
            var issueProjectName = entry.substring(hostSeparatorIndex + 1);
            return host.project(issueProjectName);
        } else {
            throw new ConfigurationError("Malformed issue project entry");
        }
    }

    public static BotRunnerConfiguration parse(JSONObject config, Path cwd) throws ConfigurationError {
        return new BotRunnerConfiguration(config, cwd);
    }

    public static BotRunnerConfiguration parse(JSONObject config) throws ConfigurationError {
        return parse(config, Paths.get("."));
    }

    public BotConfiguration perBotConfiguration(String botName) throws ConfigurationError {
        if (!config.contains(botName)) {
            throw new ConfigurationError("No configuration for bot name: " + botName);
        }

        return new BotConfiguration() {
            @Override
            public Path storageFolder() {
                if (!config.contains("storage") || !config.get("storage").contains("path")) {
                    try {
                        return Files.createTempDirectory("storage-" + botName);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return Paths.get(config.get("storage").get("path").asString()).resolve(botName);
            }

            @Override
            public HostedRepository repository(String name) {
                try {
                    var entry = parseRepositoryEntry(name);
                    return entry.repository;
                } catch (ConfigurationError configurationError) {
                    throw new RuntimeException("Couldn't find repository with name: " + name, configurationError);
                }
            }

            @Override
            public IssueProject issueProject(String name) {
                try {
                    return parseIssueProjectEntry(name);
                } catch (ConfigurationError configurationError) {
                    throw new RuntimeException("Couldn't find issue project with name: " + name, configurationError);
                }
            }

            @Override
            public ContinuousIntegration continuousIntegration(String name) {
                if (continuousIntegrations.containsKey(name)) {
                    return continuousIntegrations.get(name);
                }
                throw new RuntimeException("Couldn't find continuous integration with name: " + name);
            }

            @Override
            public String repositoryRef(String name) {
                try {
                    var entry = parseRepositoryEntry(name);
                    return entry.ref;
                } catch (ConfigurationError configurationError) {
                    throw new RuntimeException("Couldn't find repository with name: " + name, configurationError);
                }
            }

            @Override
            public String repositoryName(String name) {
                var refIndex = name.indexOf(':');
                if (refIndex >= 0) {
                    name = name.substring(0, refIndex);
                }
                var orgIndex = name.lastIndexOf('/');
                if (orgIndex >= 0) {
                    name = name.substring(orgIndex + 1);
                }
                return name;
            }

            @Override
            public JSONObject specific() {
                return config.get(botName).asObject();
            }
        };
    }

    /**
     * The amount of time to wait between each invocation of Bot.getPeriodicItems.
     * @return
     */
    Duration scheduledExecutionPeriod() {
        if (!config.contains("runner") || !config.get("runner").contains("interval")) {
            log.info("No WorkItem invocation period defined, using default value");
            return Duration.ofSeconds(10);
        } else {
            return Duration.parse(config.get("runner").get("interval").asString());
        }
    }

    /**
     * The amount of time to wait between runs of the RestResponseCache evictions.
     * @return
     */
    Duration cacheEvictionInterval() {
        if (!config.contains("runner") || !config.get("runner").contains("cache_eviction_interval")) {
            var defaultValue = Duration.ofMinutes(5);
            log.info("No cache eviction interval defined, using default value " + defaultValue);
            return defaultValue;
        } else {
            return Duration.parse(config.get("runner").get("cache_eviction_interval").asString());
        }
    }

    /**
     * Number of WorkItems to execute in parallel.
     * @return
     */
    Integer concurrency() {
        if (!config.contains("runner") || !config.get("runner").contains("concurrency")) {
            log.info("WorkItem concurrency not defined, using default value");
            return 2;
        } else {
            return config.get("runner").get("concurrency").asInt();
        }
    }

    /**
     * Folder that WorkItems may use to store temporary data.
     * @return
     */
    Path scratchFolder() {
        if (!config.contains("scratch") || !config.get("scratch").contains("path")) {
            try {
                log.warning("No scratch folder defined, creating a temporary folder");
                return Files.createTempDirectory("botrunner");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Paths.get(config.get("scratch").get("path").asString());
    }

    static class HttpContextConfiguration {
        private final String path;
        private final HttpHandler handler;

        private HttpContextConfiguration(String path, HttpHandler handler) {
            this.path = path;
            this.handler = handler;
        }

        String path() {
            return path;
        }

        HttpHandler handler() {
            return handler;
        }
    }

    static class HttpServerConfiguration {
        private final int port;
        private final List<HttpContextConfiguration> contexts;

        private HttpServerConfiguration(int port, List<HttpContextConfiguration> contexts) {
            this.port = port;
            this.contexts = contexts;
        }

        int port() {
            return port;
        }

        List<HttpContextConfiguration> contexts() {
            return contexts;
        }
    }

    Optional<HttpServerConfiguration> httpServer(BotRunner runner) {
        if (!config.contains("http-server")) {
            return Optional.empty();
        }

        Map<String, BiFunction<BotRunner, JSONObject, HttpHandler>> factories = Map.of(
            WebhookHandler.name(), WebhookHandler::create,
            MetricsHandler.name(), MetricsHandler::create,
            ReadinessHandler.name(), ReadinessHandler::create,
            LivenessHandler.name(), LivenessHandler::create,
            ProfileHandler.name(), ProfileHandler::create,
            VersionHandler.name(), VersionHandler::create
        );
        var contexts = new ArrayList<HttpContextConfiguration>();
        var port = config.get("http-server").get("port").asInt();
        for (var field : config.get("http-server").fields()) {
            if (field.name().startsWith("/")) {
                var path = field.name();
                var type = field.value().get("type").asString();
                if (!factories.containsKey(type)) {
                    throw new RuntimeException("Unknown kind of HTTP handler: " + type);
                }
                var handler = factories.get(type).apply(runner, field.value().asObject());
                contexts.add(new HttpContextConfiguration(path, handler));
            }
        }

        return Optional.of(new HttpServerConfiguration(port, contexts));
    }

    Duration watchdogTimeout() {
        if (!config.contains("runner") || !config.get("runner").contains("watchdog")) {
            log.info("No WorkItem watchdog timeout defined, using default value");
            return Duration.ofMinutes(30);
        } else {
            return Duration.parse(config.get("runner").get("watchdog").asString());
        }
    }

    Duration watchdogWarnTimeout() {
        if (!config.contains("runner") || !config.get("runner").contains("watchdog_warn")) {
            log.info("No WorkItem watchdog_warn timeout defined, using watchdog value");
            return watchdogTimeout();
        } else {
            return Duration.parse(config.get("runner").get("watchdog_warn").asString());
        }
    }
}
