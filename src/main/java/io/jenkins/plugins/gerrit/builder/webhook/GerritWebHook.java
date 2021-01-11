// Copyright (C) 2018 GerritForge Ltd
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.jenkins.plugins.gerrit.builder.webhook;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.servlet.http.HttpServletRequest;

import org.acegisecurity.Authentication;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.gerrit.builder.PluginImpl;
import jenkins.model.Jenkins;

@Extension
public class GerritWebHook implements UnprotectedRootAction {
    private static final Logger log = LoggerFactory.getLogger(GerritWebHook.class);
    private static final Gson gson = new Gson();

    public static final String URLNAME = "gerrit-builder-webhook";
    private static final Set<String> ALLOWED_TYPES = Sets.newHashSet("ref-updated", "change-deleted",
            "change-abandoned", "change-merged", "change-restored", "patchset-created", "private-state-changed",
            "wip-state-changed", "topic-changed", "vote-deleted", "comment-added");

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URLNAME;
    }

    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException {
        getBody(req, rsp).ifPresent(projectEvent -> {
            String username = "anonymous";
            Authentication authentication = Jenkins.getAuthentication();
            if (authentication != null) {
                username = authentication.getName();
            }

            log.info("Got web hook! GerritWebHook invoked by user '{}' for event: {}", username, projectEvent);

            try (ACLContext acl = ACL.as(ACL.SYSTEM)) {
                log.info("System: GerritWebHook invoked by user '{}' for event: {}", username, projectEvent);
                PluginImpl plugin = PluginImpl.getInstance();
                if (plugin != null) {
                    plugin.webHookEvent(projectEvent);
                }
            }
        });
    }

    @VisibleForTesting
    Optional<GerritProjectEvent> getBody(HttpServletRequest req, StaplerResponse rsp) throws IOException {
        if (req.getMethod().equals("POST")) {
            try (InputStreamReader is = new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject eventJson = gson.fromJson(is, JsonObject.class);
                log.info("eventJson = " + eventJson);
                if (eventJson != null) {
                    JsonPrimitive eventType = eventJson.getAsJsonPrimitive("type");
                    if (eventType != null && ALLOWED_TYPES.contains(eventType.getAsString())) {
                        GerritProjectEvent event = gson.fromJson(eventJson, GerritProjectEvent.class);
                        if (event.type.equals("ref-updated")) {
                            if (event.refUpdate.refName.endsWith("/meta")) {
                                log.info("Skipping ref-updated for meta branch");
                                return Optional.empty();
                            }
                        } else if (event.type.equals("vote-deleted") || event.type.equals("comment-added")) {
                            for (Approval approval : event.approvals) {
                                if (approval.type.equals("Verified")) {
                                    if (approval.value == 0 && approval.oldValue != 0) {
                                        log.info("Verified: 0 for event {}, oldValue was {}", event.type,
                                                approval.oldValue);
                                        return Optional.of(event);
                                    }
                                }
                            }
                            log.info("Skipping non important {}", event.type);
                            return Optional.empty();
                        } else {
                            return Optional.of(event);
                        }
                    }
                } else {
                    log.info("Invalid JSON in webhook request");
                    rsp.sendError(400, "Invalid JSON in webhook request");
                }
                return Optional.empty();
            }
        } else {
            log.info("Invalid method {} != POST in webhook", req.getMethod());
            rsp.sendError(400, "Invalid HTTP method, use POST");
            return Optional.empty();
        }
    }

    public static GerritWebHook get() {
        return Jenkins.get().getExtensionList(RootAction.class).get(GerritWebHook.class);
    }

    @Nonnull
    public static Jenkins getJenkinsInstance() throws IllegalStateException {
        return Jenkins.get();
    }
}
