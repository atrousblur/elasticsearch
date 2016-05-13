/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.cleaner;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.marvel.MonitoringSettings;
import org.elasticsearch.marvel.MonitoredSystem;
import org.elasticsearch.marvel.agent.exporter.Exporter;
import org.elasticsearch.marvel.agent.exporter.Exporters;
import org.elasticsearch.marvel.agent.exporter.MarvelTemplateUtils;
import org.elasticsearch.marvel.agent.exporter.MonitoringDoc;
import org.elasticsearch.marvel.agent.resolver.MonitoringIndexNameResolver;
import org.elasticsearch.marvel.test.MarvelIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.VersionUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.Locale;

import static org.elasticsearch.test.ESIntegTestCase.Scope.TEST;

@ClusterScope(scope = TEST, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public abstract class AbstractIndicesCleanerTestCase extends MarvelIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder settings = Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(MonitoringSettings.INTERVAL.getKey(), "-1");
        return settings.build();
    }

    public void testNothingToDelete() throws Exception {
        internalCluster().startNode();

        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(0));
        assertIndicesCount(0);
    }

    public void testDeleteIndex() throws Exception {
        internalCluster().startNode();

        createTimestampedIndex(now().minusDays(10));
        assertIndicesCount(1);

        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(10));
        assertIndicesCount(0);
    }

    public void testIgnoreCurrentDataIndex() throws Exception {
        internalCluster().startNode();

        // Will be deleted
        createTimestampedIndex(now().minusDays(10));

        // Won't be deleted
        createDataIndex(now().minusDays(10));
        assertIndicesCount(2);

        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(0));
        assertIndicesCount(1);
    }

    public void testIgnoreDataIndicesInOtherVersions() throws Exception {
        internalCluster().startNode();

        // Will be deleted
        createTimestampedIndex(now().minusDays(10));

        // Won't be deleted
        createIndex(MonitoringSettings.LEGACY_DATA_INDEX_NAME, now().minusYears(1));
        createDataIndex(now().minusDays(10), 0);
        createDataIndex(now().minusDays(10), 1);
        assertIndicesCount(4);

        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(0));
        assertIndicesCount(3);
    }

    public void testIgnoreCurrentTimestampedIndex() throws Exception {
        internalCluster().startNode();

        // Will be deleted
        createTimestampedIndex(now().minusDays(10));

        // Won't be deleted
        createTimestampedIndex(now());
        assertIndicesCount(2);

        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(0));
        assertIndicesCount(1);
    }

    public void testIgnoreTimestampedIndicesInOtherVersions() throws Exception {
        internalCluster().startNode();

        // Will be deleted
        createTimestampedIndex(now().minusDays(10));

        // Won't be deleted
        createTimestampedIndex(now().minusDays(10), 0);
        createTimestampedIndex(now().minusDays(10), 1);
        createTimestampedIndex(now().minusDays(10), Integer.MAX_VALUE);
        assertIndicesCount(4);

        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(0));
        assertIndicesCount(3);
    }

    public void testDeleteIndices() throws Exception {
        internalCluster().startNode();

        CleanerService.Listener listener = getListener();

        final DateTime now = now();
        createTimestampedIndex(now.minusYears(1));
        createTimestampedIndex(now.minusMonths(6));
        createTimestampedIndex(now.minusMonths(1));
        createTimestampedIndex(now.minusDays(10));
        createTimestampedIndex(now.minusDays(1));
        assertIndicesCount(5);

        // Clean indices that have expired two years ago
        listener.onCleanUpIndices(years(2));
        assertIndicesCount(5);

        // Clean indices that have expired 8 months ago
        listener.onCleanUpIndices(months(8));
        assertIndicesCount(4);

        // Clean indices that have expired 3 months ago
        listener.onCleanUpIndices(months(3));
        assertIndicesCount(3);

        // Clean indices that have expired 15 days ago
        listener.onCleanUpIndices(days(15));
        assertIndicesCount(2);

        // Clean indices that have expired 7 days ago
        listener.onCleanUpIndices(days(7));
        assertIndicesCount(1);

        // Clean indices until now
        listener.onCleanUpIndices(days(0));
        assertIndicesCount(0);
    }

    public void testRetentionAsGlobalSetting() throws Exception {
        final int max = 10;
        final int retention = randomIntBetween(1, max);
        internalCluster().startNode(Settings.builder().put(MonitoringSettings.HISTORY_DURATION.getKey(),
                String.format(Locale.ROOT, "%dd", retention)));

        final DateTime now = now();
        for (int i = 0; i < max; i++) {
            createTimestampedIndex(now.minusDays(i));
        }
        assertIndicesCount(max);

        // Clean indices that have expired for N days, as specified in the global retention setting
        CleanerService.Listener listener = getListener();
        listener.onCleanUpIndices(days(retention));
        assertIndicesCount(retention);
    }

    protected CleanerService.Listener getListener() {
        Exporters exporters = internalCluster().getInstance(Exporters.class, internalCluster().getMasterName());
        for (Exporter exporter : exporters) {
            if (exporter instanceof CleanerService.Listener) {
                return (CleanerService.Listener) exporter;
            }
        }
        throw new IllegalStateException("unable to find listener");
    }

    private MonitoringDoc randomMonitoringDoc() {
        return new MonitoringDoc(randomFrom(MonitoredSystem.values()).getSystem(), VersionUtils.randomVersion(random()).toString());
    }

    /**
     * Creates a monitoring data index in a given version.
     */
    protected void createDataIndex(DateTime creationDate) {
        createDataIndex(creationDate, MarvelTemplateUtils.TEMPLATE_VERSION);
    }

    /**
     * Creates a monitoring data index in a given version.
     */
    protected void createDataIndex(DateTime creationDate, int version) {
        createIndex(new MockDataIndexNameResolver(version).index(randomMonitoringDoc()), creationDate);
    }

    /**
     * Creates a monitoring timestamped index using the current template version.
     */
    protected void createTimestampedIndex(DateTime creationDate) {
        createTimestampedIndex(creationDate, MarvelTemplateUtils.TEMPLATE_VERSION);
    }

    /**
     * Creates a monitoring timestamped index using a given template version.
     */
    protected void createTimestampedIndex(DateTime creationDate, int version) {
        MonitoringDoc monitoringDoc = randomMonitoringDoc();
        monitoringDoc.setTimestamp(creationDate.getMillis());

        MonitoringIndexNameResolver.Timestamped resolver =
                new MockTimestampedIndexNameResolver(MonitoredSystem.ES, Settings.EMPTY, version);
        createIndex(resolver.index(monitoringDoc), creationDate);
    }

    protected abstract void createIndex(String name, DateTime creationDate);

    protected abstract void assertIndicesCount(int count) throws Exception;

    private static TimeValue years(int years) {
        DateTime now = now();
        return TimeValue.timeValueMillis(now.getMillis() - now.minusYears(years).getMillis());
    }

    private static TimeValue months(int months) {
        DateTime now = now();
        return TimeValue.timeValueMillis(now.getMillis() - now.minusMonths(months).getMillis());
    }

    private static TimeValue days(int days) {
        return TimeValue.timeValueHours(days * 24);
    }

    private static DateTime now() {
        return new DateTime(DateTimeZone.UTC);
    }
}
