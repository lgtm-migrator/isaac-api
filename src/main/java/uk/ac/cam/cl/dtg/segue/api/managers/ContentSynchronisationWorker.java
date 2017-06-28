/**
 * Copyright 2014 Stephen Cummins
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.ac.cam.cl.dtg.segue.api.managers;

import java.util.Date;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.segue.dao.content.ContentManagerException;

/**
 * Worker class to orchestrate content indexing jobs.
 * 
 * @author Stephen Cummins
 * 
 */
public class ContentSynchronisationWorker implements Callable<String> {
    private static final Logger log = LoggerFactory.getLogger(ContentSynchronisationWorker.class);

    private ContentVersionController contentVersionController;
    private String version; // null for latest, set for a particular version

    private final Date dateRequested;

    /**
     * Create a synch worker providing a contentVersionController and a version to index.
     * 
     * @param contentVersionController
     *            - the object providing access to high level sync methods.
     * @param version
     *            - the version of the content to attempt to index.
     */
    public ContentSynchronisationWorker(final ContentVersionController contentVersionController, final String version) {
        this.contentVersionController = contentVersionController;
        this.version = version;
        this.dateRequested = new Date();
    }

    /**
     * Create a synch worker providing a contentVersionController. This constructor assumes that the latest version of
     * the content available should be indexed.
     * 
     * @param contentVersionController
     *            - the object providing access to high level sync methods.
     */
    public ContentSynchronisationWorker(final ContentVersionController contentVersionController) {
        this(contentVersionController, null);
    }

    @Override
    public String call() {
        // Verify with Content manager that we can sync to the version requested
        // / get the latest one
        log.info(String.format("Starting synchronisation task (%s) for the content repository.", version));

        if (null == version) {
            // assume we are just trying to get the latest version when we have
            // a null version field.
            version = contentVersionController.getContentManager().getLatestVersionId();
        } else if (!contentVersionController.getContentManager().isValidVersion(version)) {
            log.error("Error while trying to run index operation for version: " + version + " . Terminating Sync Job.");
            // call the content controller to tell them we failed to sync the
            // version requested
            contentVersionController.syncJobCompleteCallback(version, false);
            return null;
        }

        // trigger index operation with content Manager.
        try {
            this.contentVersionController.getContentManager().ensureCache(version);
            // successful indexing operation.
            // Call the content controller to tell them we have finished our
            // job and they may like to do something.
            contentVersionController.syncJobCompleteCallback(version, true);

            log.info(String.format("Synchronisation task completed for (%s).", version));
            return version;
        } catch (ContentManagerException e) {
            log.error(String.format("Error while trying to index version: %s.", version), e);
            contentVersionController.syncJobCompleteCallback(version, false);
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ContentSynchronisationWorker [version=");
        builder.append(version);
        builder.append(", dateRequested=");
        builder.append(dateRequested);
        builder.append("]");
        return builder.toString();
    }
}