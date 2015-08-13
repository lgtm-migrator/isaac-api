/**
 * Copyright 2015 Stephen Cummins
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

import static com.google.common.collect.Maps.immutableEntry;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.api.client.util.Lists;
import com.google.api.client.util.Maps;
import com.google.inject.Inject;

import uk.ac.cam.cl.dtg.segue.api.Constants;
import uk.ac.cam.cl.dtg.segue.api.Constants.BooleanOperator;
import uk.ac.cam.cl.dtg.segue.dao.ResourceNotFoundException;
import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;
import uk.ac.cam.cl.dtg.segue.dao.content.ContentManagerException;
import uk.ac.cam.cl.dtg.segue.dos.PgUserNotifications;
import uk.ac.cam.cl.dtg.segue.dos.UserNotification;
import uk.ac.cam.cl.dtg.segue.dos.UserNotification.NotificationStatus;
import uk.ac.cam.cl.dtg.segue.dto.ResultsWrapper;
import uk.ac.cam.cl.dtg.segue.dto.content.ContentDTO;
import uk.ac.cam.cl.dtg.segue.dto.content.NotificationDTO;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;

/**
 * This class is responsible for selecting notifications from various sources so that users can be told about them.
 *
 */
public class NotificationPicker {
    private PgUserNotifications notifications;
    private ContentVersionController contentVersionController;

    /**
     * @param contentVersionController
     *            - so we can lookup notifications created in the segue content system.
     * @param notifications
     *            - the DAO allowing the recording of which notifications have been shown to whom.
     */
    @Inject
    public NotificationPicker(final ContentVersionController contentVersionController,
            final PgUserNotifications notifications) {
        this.contentVersionController = contentVersionController;
        this.notifications = notifications;
    }

    /**
     * getAvailableNotificationsForUser.
     * 
     * @param user
     *            to select notifications for.
     * @return the list of content to show to the user.
     * @throws ContentManagerException
     *             - if something goes wrong looking up the content.
     * @throws SegueDatabaseException
     *             - if something goes wrong consulting the personalisation database.
     */
    public List<ContentDTO> getAvailableNotificationsForUser(final RegisteredUserDTO user)
            throws ContentManagerException, SegueDatabaseException {
        // get users notification record
        Map<Entry<BooleanOperator, String>, List<String>> fieldsToMatch = Maps.newHashMap();
        List<String> newArrayList = Lists.newArrayList();

        newArrayList.add("notification");

        fieldsToMatch.put(immutableEntry(BooleanOperator.AND, Constants.TYPE_FIELDNAME), newArrayList);

        ResultsWrapper<ContentDTO> allContentNotifications = contentVersionController.getContentManager()
                .findByFieldNames(contentVersionController.getLiveVersion(), fieldsToMatch, 0, -1);

        Map<String, UserNotification> listOfRecordedNotifications = getMapOfRecordedNotifications(user);

        List<ContentDTO> resultsToReturn = Lists.newArrayList();

        for (ContentDTO c : allContentNotifications.getResults()) {
            UserNotification record = listOfRecordedNotifications.get(c.getId());

            if (null == record) {
                resultsToReturn.add(c);
            } else if (record.getStatus().equals(NotificationStatus.POSTPONED)) {
                Calendar postPoneExpiry = Calendar.getInstance();
                postPoneExpiry.setTime(record.getCreated());
                postPoneExpiry.add(Calendar.SECOND, Constants.CACHE_FOR_ONE_WEEK);

                if (new Date().after(postPoneExpiry.getTime())) {
                    resultsToReturn.add(c);
                }
            }
        }

        return resultsToReturn;
    }

    /**
     * getListOfRecordedNotifications.
     * 
     * @param user
     *            - to lookup the notification history for.
     * @return a map of NotificationId --> UserNotificationRecord.
     * @throws SegueDatabaseException
     *             - if something goes wrong with the DB io step.
     */
    public Map<String, UserNotification> getMapOfRecordedNotifications(final RegisteredUserDTO user)
            throws SegueDatabaseException {
        Map<String, UserNotification> result = Maps.newHashMap();

        List<UserNotification> userNotifications = notifications.getUserNotifications(user.getDbId());

        for (UserNotification recordedNotification : userNotifications) {
            result.put(recordedNotification.getContentNotificationId(), recordedNotification);
        }

        return result;
    }

    /**
     * Allows notifications to be dismissed on a per user basis.
     * 
     * @param user
     *            - that the notification pertains to.
     * @param notificationId
     *            - the id of the notification
     * @param status
     *            - the status of the notification e.g. dismissed, postponed, disabled
     * @throws SegueDatabaseException
     *             - if something goes wrong with the DB io step.
     * @throws ContentManagerException
     *             - if something goes wrong looking up the content.
     */
    public void recordNotificationAction(final RegisteredUserDTO user, final String notificationId,
            final NotificationStatus status) throws SegueDatabaseException, ContentManagerException {
        ContentDTO notification = contentVersionController.getContentManager().getContentById(
                contentVersionController.getLiveVersion(), notificationId);

        if (null == notification) {
            throw new ResourceNotFoundException(String.format(
                    "The resource with id: %s and type Notification could not be found.", notificationId));
        }

        // update the users record with the action they have taken.
        notifications.saveUserNotification(user.getDbId(), notificationId, status);
    }

    /**
     * getNotificationById.
     * @param notificationId - the id of the notification.
     * @return get the notification content dto.
     * @throws ResourceNotFoundException
     *             - if we can't find the item of interest.
     * @throws ContentManagerException
     *             - if something goes wrong looking up the content.
     */
    public ContentDTO getNotificationById(final String notificationId) throws ContentManagerException,
            ResourceNotFoundException {
        // get available notifications that still can be displayed
        ContentDTO notification = contentVersionController.getContentManager().getContentById(
                contentVersionController.getLiveVersion(), notificationId);

        if (notification instanceof NotificationDTO && notification != null) {
            return notification;
        } else {
            throw new ResourceNotFoundException(String.format(
                    "The resource with id: %s and type Notification could not be found.", notificationId));
        }
    }
}