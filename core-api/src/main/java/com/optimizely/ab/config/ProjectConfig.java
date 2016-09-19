/**
 *
 *    Copyright 2016, Optimizely
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.optimizely.ab.config.audience.Audience;
import com.optimizely.ab.config.audience.Condition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.Immutable;

/**
 * Represents the Optimizely Project configuration.
 *
 * @see <a href="http://developers.optimizely.com/server/reference/index.html#json">Project JSON</a>
 */
@Immutable
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectConfig {

    public static final String V1 = "1";
    public static final String V2 = "2";

    private final String accountId;
    private final String projectId;
    private final String revision;
    private final String version;
    private final List<Group> groups;
    private final List<Experiment> experiments;
    private final List<Attribute> attributes;
    private final List<EventType> events;
    private final List<Audience> audiences;

    // convenience mappings for efficient lookup
    private final Map<String, Experiment> experimentKeyMapping;
    private final Map<String, Attribute> attributeKeyMapping;
    private final Map<String, EventType> eventNameMapping;
    private final Map<String, Audience> audienceIdMapping;
    private final Map<String, Experiment> experimentIdMapping;
    private final Map<String, Group> groupIdMapping;

    public ProjectConfig(String accountId, String projectId, String version, String revision, List<Group> groups,
                         List<Experiment> experiments, List<Attribute> attributes, List<EventType> eventType,
                         List<Audience> audiences) {

        this.accountId = accountId;
        this.projectId = projectId;
        this.version = version;
        this.revision = revision;

        this.groups = Collections.unmodifiableList(groups);
        List<Experiment> allExperiments = new ArrayList<Experiment>();
        allExperiments.addAll(experiments);
        allExperiments.addAll(aggregateGroupExperiments(groups));
        this.experiments = Collections.unmodifiableList(allExperiments);
        this.attributes = Collections.unmodifiableList(attributes);
        this.events = Collections.unmodifiableList(eventType);
        this.audiences = Collections.unmodifiableList(audiences);

        // generate the name mappers
        this.experimentKeyMapping = ProjectConfigUtils.generateNameMapping(this.experiments);
        this.attributeKeyMapping = ProjectConfigUtils.generateNameMapping(attributes);
        this.eventNameMapping = ProjectConfigUtils.generateNameMapping(events);

        // generate audience id to audience mapping
        this.audienceIdMapping = ProjectConfigUtils.generateIdMapping(audiences);
        this.experimentIdMapping = ProjectConfigUtils.generateIdMapping(this.experiments);
        this.groupIdMapping = ProjectConfigUtils.generateIdMapping(groups);
    }

    private List<Experiment> aggregateGroupExperiments(List<Group> groups) {
        List<Experiment> groupExperiments = new ArrayList<Experiment>();
        for (Group group : groups) {
            groupExperiments.addAll(group.getExperiments());
        }

        return groupExperiments;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getVersion() {
        return version;
    }

    public String getRevision() {
        return revision;
    }

    public List<Group> getGroups() {
        return groups;
    }

    public List<Experiment> getExperiments() {
        return experiments;
    }

    public List<String> getExperimentIdsForGoal(String goalKey) {
        EventType goal;
        if ((goal = eventNameMapping.get(goalKey)) != null) {
            return goal.getExperimentIds();
        }

        return Collections.emptyList();
    }

    public List<Attribute> getAttributes() {
        return attributes;
    }

    public List<EventType> getEventTypes() {
        return events;
    }

    public List<Audience> getAudiences() {
        return audiences;
    }

    public Condition getAudienceConditionsFromId(String audienceId) {
        Audience audience = audienceIdMapping.get(audienceId);

        return audience != null ? audience.getConditions() : null;
    }

    public Map<String, Experiment> getExperimentKeyMapping() {
        return experimentKeyMapping;
    }

    public Map<String, Attribute> getAttributeKeyMapping() {
        return attributeKeyMapping;
    }

    public Map<String, EventType> getEventNameMapping() {
        return eventNameMapping;
    }

    public Map<String, Audience> getAudienceIdMapping() {
        return audienceIdMapping;
    }

    public Map<String, Experiment> getExperimentIdMapping() {
        return experimentIdMapping;
    }

    public Map<String, Group> getGroupIdMapping() {
        return groupIdMapping;
    }

    @Override
    public String toString() {
        return "ProjectConfig{" +
               "accountId='" + accountId + '\'' +
               ", projectId='" + projectId + '\'' +
               ", revision='" + revision + '\'' +
               ", version='" + version + '\'' +
               ", groups=" + groups +
               ", experiments=" + experiments +
               ", attributes=" + attributes +
               ", events=" + events +
               ", audiences=" + audiences +
               ", experimentKeyMapping=" + experimentKeyMapping +
               ", attributeKeyMapping=" + attributeKeyMapping +
               ", eventNameMapping=" + eventNameMapping +
               ", audienceIdMapping=" + audienceIdMapping +
               '}';
    }
}
