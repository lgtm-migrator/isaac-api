/**
 * Copyright 2017 Dan Underwood
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

package uk.ac.cam.cl.dtg.segue.dao.kafkaStreams.userStatistics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Maps;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.connect.json.JsonDeserializer;
import org.apache.kafka.connect.json.JsonSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.cam.cl.dtg.isaac.api.managers.IGameManager;
import uk.ac.cam.cl.dtg.isaac.dos.GameboardCreationMethod;
import uk.ac.cam.cl.dtg.isaac.dto.GameboardDTO;
import uk.ac.cam.cl.dtg.isaac.dto.GameboardItem;
import uk.ac.cam.cl.dtg.segue.api.Constants;
import uk.ac.cam.cl.dtg.segue.api.managers.IUserAccountManager;
import uk.ac.cam.cl.dtg.segue.api.userAlerts.IAlertListener;
import uk.ac.cam.cl.dtg.segue.api.userAlerts.UserAlertsWebSocket;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoUserException;
import uk.ac.cam.cl.dtg.segue.dao.ILogManager;
import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;
import uk.ac.cam.cl.dtg.segue.dao.kafkaStreams.KafkaTopicManager;
import uk.ac.cam.cl.dtg.segue.dos.IUserAlert;
import uk.ac.cam.cl.dtg.segue.dos.PgUserAlert;
import uk.ac.cam.cl.dtg.segue.dos.QuestionValidationResponse;
import uk.ac.cam.cl.dtg.segue.dos.users.Role;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;
import uk.ac.cam.cl.dtg.segue.quiz.IQuestionAttemptManager;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static uk.ac.cam.cl.dtg.segue.api.Constants.LONGEST_STREAK_REACHED;
import static uk.ac.cam.cl.dtg.segue.api.Constants.STREAK_UPDATED;

/**
 * Concrete Kafka streams processing application for generating user statistics
 *  @author Dan Underwood
 */
public class UserStatisticsStreamsApplication {

    private static final Logger log = LoggerFactory.getLogger(UserStatisticsStreamsApplication.class);
    private static final Logger kafkaLog = LoggerFactory.getLogger("kafkaStreamsLogger");

    private static final Serde<String> StringSerde = Serdes.String();
    private static final Serde<JsonNode> JsonSerde = Serdes.serdeFrom(new JsonSerializer(), new JsonDeserializer());
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static List<String> bookTags = ImmutableList.of("phys_book_gcse", "physics_skills_14", "chemistry_16");

    private KafkaStreams streams;

    private static final String streamsAppName = "streamsapp_user_stats";
    private static final String streamsAppVersion = "v1.3";
    private static Boolean streamThreadRunning;
    private static Long streamAppStartTime = System.currentTimeMillis();

    /**
     * Constructor
     * @param globalProperties
     *              - properties object containing global variables
     * @param kafkaTopicManager
     *              - manager for kafka topic administration
     */
    public UserStatisticsStreamsApplication(final PropertiesLoader globalProperties,
                                            final KafkaTopicManager kafkaTopicManager,
                                            final IQuestionAttemptManager questionAttemptManager,
                                            final IUserAccountManager userAccountManager,
                                            final IGameManager gameManager) {


        Properties streamsConfiguration = new Properties();
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, streamsAppName + "-" + streamsAppVersion);
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG,
                globalProperties.getProperty("KAFKA_HOSTNAME") + ":" + globalProperties.getProperty("KAFKA_PORT"));
        streamsConfiguration.put(StreamsConfig.STATE_DIR_CONFIG, globalProperties.getProperty("KAFKA_STREAMS_STATE_DIR"));
        streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 10 * 1000);
        streamsConfiguration.put(StreamsConfig.METADATA_MAX_AGE_CONFIG, 10 * 1000);
        streamsConfiguration.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);
        streamsConfiguration.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, globalProperties.getProperty("KAFKA_REPLICATION_FACTOR"));
        streamsConfiguration.put(StreamsConfig.consumerPrefix(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        streamsConfiguration.put(StreamsConfig.consumerPrefix(ConsumerConfig.METADATA_MAX_AGE_CONFIG), 45 * 1000);
        streamsConfiguration.put(StreamsConfig.producerPrefix(ProducerConfig.METADATA_MAX_AGE_CONFIG), 45 * 1000);
        streamsConfiguration.put(StreamsConfig.consumerPrefix(ConsumerConfig.MAX_POLL_RECORDS_CONFIG), 250);
        streamsConfiguration.put(StreamsConfig.consumerPrefix(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG), 60000);


        // ensure topics exist before attempting to consume

        // logged events
        List<ConfigEntry> loggedEventsConfigs = Lists.newLinkedList();
        loggedEventsConfigs.add(new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(-1)));
        kafkaTopicManager.ensureTopicExists(Constants.KAFKA_TOPIC_LOGGED_EVENTS, loggedEventsConfigs);

        // local store changelog topics
        List<ConfigEntry> changelogConfigs = Lists.newLinkedList();
        changelogConfigs.add(new ConfigEntry(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
        kafkaTopicManager.ensureTopicExists(streamsAppName + "-" + streamsAppVersion + "-localstore_user_snapshot-changelog", changelogConfigs);

        final AtomicLong lastLagLog = new AtomicLong(0);
        final AtomicBoolean wasLagging = new AtomicBoolean(true);

        // raw logged events incoming data stream from kafka
        KStreamBuilder builder = new KStreamBuilder();
        KStream<String, JsonNode> rawLoggedEvents = builder.stream(StringSerde, JsonSerde, Constants.KAFKA_TOPIC_LOGGED_EVENTS)
                .filterNot(
                        (k, v) -> v.path("anonymous_user").asBoolean()
                ).peek(
                        (k,v) -> {
                            long lag = System.currentTimeMillis() - v.get("timestamp").asLong();
                            if (lag > 1000) {

                                if (System.currentTimeMillis() - lastLagLog.get() > 10000) {
                                    kafkaLog.info(String.format("User statistics stream lag: %.02f hours (%.03f s).", lag / 3600000.0, lag / 1000.0));
                                    lastLagLog.set(System.currentTimeMillis());
                                }

                            } else if (wasLagging.get()) {
                                wasLagging.set(false);
                                kafkaLog.info("User statistics stream processing caught up.");
                            }
                        }
                );

        // process raw logged events
        streamProcess(rawLoggedEvents, questionAttemptManager, userAccountManager, gameManager);

        // need to make state stores queryable globally, as we often have 2 versions of API running concurrently, hence 2 streams app instances
        // aggregations are saved to a local state store per streams app instance and update a changelog topic in Kafka
        // we can use this changelog to populate a global state store for all streams app instances
        builder.globalTable(StringSerde, JsonSerde,streamsAppName + "-" + streamsAppVersion + "-localstore_user_snapshot-changelog", "globalstore_user_snapshot-" + streamsAppVersion);

        // use the builder and the streams configuration we set to setup and start a streams object
        streams = new KafkaStreams(builder, streamsConfiguration);

        // handling fatal streams app exceptions
        streams.setUncaughtExceptionHandler(
                (thread, throwable) -> {

                    // a bit hacky, but we know that the rebalance-inducing app death is caused by a CommitFailedException that is two levels deep into the stack trace
                    // otherwsie we get a StreamsException which is too general
                    if (throwable.getCause().getCause() instanceof CommitFailedException) {
                        streamThreadRunning = false;
                        log.info("Site statistics streams app no longer running.");
                    }
                }
        );

        streams.start();

        // return when streams instance is initialized
        while (true) {

            if (streams.state().isCreatedOrRunning())
                streamThreadRunning = true;
                break;
        }

        kafkaLog.info("User statistics streams application started.");
    }


    /**
     * This method contains the logic that transforms the incoming stream
     *
     * @param rawStream
     *          - the input stream
     */
    private static void streamProcess(KStream<String, JsonNode> rawStream,
                                      final IQuestionAttemptManager questionAttemptManager,
                                      final IUserAccountManager userAccountManager,
                                      final IGameManager gameManager) {

        // map the key-value pair to one where the key is always the user id
        KStream<String, JsonNode> mappedStream = rawStream
                .map(
                        (k, v) -> {

                            //TODO: this is a bit hacky, currently this event is recorded as being associated with the admin ID, so need to extract out the event attendee ID
                            if (v.path("event_type").asText().equals("ADMIN_EVENT_ATTENDANCE_RECORDED")) {
                                return new KeyValue<>(v.path("event_details").path("userId").asText(), v);
                            }

                            return new KeyValue<>(v.path("user_id").asText(), v);
                        }
                );

        // user question answer streaks
        mappedStream.groupByKey(StringSerde, JsonSerde)
                .aggregate(
                        // initializer
                        new UserStatisticsSnapshotInitializer(),
                        // aggregator
                        (userId, latestEvent, userSnapshot) -> {

                            try {

                                RegisteredUserDTO regUser = userAccountManager.getUserDTOById(Long.parseLong(userId));

                                // see if we need to augment the user record
                                if (!regUser.getRole().equals(Role.STUDENT)) {

                                    // first, lets see if we need to augment the user snapshot with teacher information
                                    if (!userSnapshot.has("teacher_record")) {
                                        ((ObjectNode) userSnapshot).set("teacher_record", getInitializedNonStudentRecord());
                                    }

                                    // non-student based event handling
                                    if (latestEvent.path("event_type").asText().equals("CREATE_USER_GROUP")) {
                                        ((ObjectNode) userSnapshot.path("teacher_record"))
                                                .put("groups_created", updateActivityCount("groups_created", userSnapshot.path("teacher_record")));
                                    }

                                    if (latestEvent.path("event_type").asText().equals("SET_NEW_ASSIGNMENT")) {
                                        ((ObjectNode) userSnapshot.path("teacher_record"))
                                                .put("assignments_set", updateActivityCount("assignments_set", userSnapshot.path("teacher_record")));

                                        // check if the assignment set contains book pages
                                        GameboardDTO gameboard = gameManager.getGameboard(latestEvent.path("event_details").path("gameboardId").asText());

                                        tagsLoop:
                                            for (GameboardItem item: gameboard.getQuestions()) {

                                                if (item.getTags() != null) {
                                                    for (String tag: item.getTags()
                                                            ) {
                                                        if (bookTags.contains(tag)) {
                                                            ((ObjectNode) userSnapshot.path("teacher_record"))
                                                                    .put("book_pages_set", updateActivityCount("book_pages_set", userSnapshot.path("teacher_record")));
                                                            break tagsLoop;
                                                        }
                                                    }
                                                }
                                            }
                                    }


                                    if (latestEvent.path("event_type").asText().equals("ADMIN_EVENT_ATTENDANCE_RECORDED")) {
                                        if (latestEvent.path("event_details").path("attended").asBoolean()) {

                                            Iterator<JsonNode> tags = latestEvent.path("event_details").path("eventTags").elements();

                                            while (tags.hasNext()) {

                                                if (tags.next().asText().equals("teacher")) {
                                                    ((ObjectNode) userSnapshot.path("teacher_record"))
                                                            .put("cpd_events_attended", updateActivityCount("cpd_events_attended", userSnapshot.path("teacher_record")));
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }

                                // snapshot updates pertaining to question answer activity
                                if (latestEvent.path("event_type").asText().equals("ANSWER_QUESTION")) {

                                    if (latestEvent.path("event_details").path("correct").asBoolean()) {
                                        ((ObjectNode) userSnapshot).set("streak_record",
                                                updateStreakRecord(userId, latestEvent, userSnapshot.path("streak_record"), questionAttemptManager));
                                    }
                                }

                                // snapshot updates pertaining to gameboard creation activity
                                if (latestEvent.path("event_type").asText().equals("CREATE_GAMEBOARD")) {

                                    String gameboardId = latestEvent.path("event_details").path("gameboardId").asText();

                                    GameboardDTO gameboard = gameManager.getGameboard(gameboardId);

                                    JsonNode gameboardCreationNode = userSnapshot.path("gameboard_record").path("creations");

                                    if (gameboard.getCreationMethod().equals(GameboardCreationMethod.BUILDER)) {
                                        ((ObjectNode) gameboardCreationNode).put("builder", updateActivityCount("builder", gameboardCreationNode));
                                    } else if (gameboard.getCreationMethod().equals(GameboardCreationMethod.FILTER)) {
                                        ((ObjectNode) gameboardCreationNode).put("filter", updateActivityCount("filter", gameboardCreationNode));
                                    }
                                }

                                // if we need to manually change a user's streak
                                if (latestEvent.path("event_type").asText().equals("ADMIN_UPDATE_USER_STREAK")) {

                                    String subUserId = latestEvent.path("event_details").path("user_id").asText();
                                    ((ObjectNode) userSnapshot).set("streak_record", updateStreakRecord(subUserId, latestEvent, userSnapshot.path("streak_record"), questionAttemptManager));
                                }

                            } catch (NoUserException e) {
                                log.error("User " + userId + " not found in Postgres DB while processing streams data!");
                            } catch (NumberFormatException | SegueDatabaseException e) {
                                log.error("Could not process user with id = " + userId + " in streams application.");
                            } catch (RuntimeException e) {
                                // streams app annoyingly dies if there is an uncaught runtime exception from any level, so we catch everything
                                e.printStackTrace();
                            }

                            return userSnapshot;
                        },
                        JsonSerde,
                        "localstore_user_snapshot"
                );
    }


    /**
     * Method to obtain the current user snapshot of activity stats
     *
     * @param user the user we are interested in
     * @return number of consecutive days a user has answered a question correctly
     */
    public Map<String, Object> getUserSnapshot(RegisteredUserDTO user) {

        Map<String, Object> userSnapshot = Maps.newHashMap();
        Map<String, Object> streakRecord = Maps.newHashMap();
        streakRecord.put("currentStreak", 0);
        streakRecord.put("largestStreak", 0);
        streakRecord.put("currentActivity", 0);

        // get the persisted snapshot document
        JsonNode snapshotRecord = streams
                .store("globalstore_user_snapshot-" + streamsAppVersion, QueryableStoreTypes.<String, JsonNode>keyValueStore())
                .get(String.valueOf(user.getId()));


        if (snapshotRecord != null) {

            // get daily streak information
            JsonNode streakNode = snapshotRecord.path("streak_record");

            Long streakEndTimestamp = streakNode.path("streak_end").asLong();

            if (streakEndTimestamp != 0) {

                // set up a streak increase deadline at midnight for the next day
                Calendar tomorrowMidnight = roundDownToDay(Calendar.getInstance());
                tomorrowMidnight.add(Calendar.DAY_OF_YEAR, 1);

                Long daysSinceStreakIncrease = TimeUnit.DAYS.convert(tomorrowMidnight.getTimeInMillis() - streakEndTimestamp, TimeUnit.MILLISECONDS);

                if (daysSinceStreakIncrease == 0) {

                    streakRecord.put("currentActivity", snapshotRecord.path("streak_record").path("current_activity").asInt());
                    streakRecord.put("currentStreak", TimeUnit.DAYS.convert(streakEndTimestamp - snapshotRecord.path("streak_record").path("streak_start").asLong(),
                            TimeUnit.MILLISECONDS));


                } else if (daysSinceStreakIncrease == 1) {

                    Integer curActivity = snapshotRecord.path("streak_record").path("current_activity").asInt();

                    if (curActivity != snapshotRecord.path("streak_record").path("activity_threshold").asInt()) {
                        streakRecord.put("currentActivity", snapshotRecord.path("streak_record").path("current_activity").asInt());
                    }

                    streakRecord.put("currentStreak", TimeUnit.DAYS.convert(streakEndTimestamp - snapshotRecord.path("streak_record").path("streak_start").asLong(),
                            TimeUnit.MILLISECONDS));

                    streakRecord.put("dailyStreakMessage", "Complete your daily question goal by the end of today to increase your streak!");
                }

                streakRecord.put("largestStreak", snapshotRecord.path("streak_record").path("largest_streak").asLong());
                userSnapshot.put("streakRecord", streakRecord);
            }

            // for all users who aren't students, augment the snapshot with additional teacher stats
            if (!user.getRole().equals(Role.STUDENT) && snapshotRecord.has("teacher_record")) {
                userSnapshot.put("teacherActivityRecord", snapshotRecord.path("teacher_record"));
            }

            userSnapshot.put("gameboardRecord", snapshotRecord.path("gameboard_record"));
        }

        return userSnapshot;
    }


    /**
     * We call this method to update the streak data for the user snapshot record
     *
     * @param userId id of the user we want to update
     * @param latestEvent json object describing the event which triggers the streak update
     * @param streakRecord the current snapshot of the streak record
     * @return the new updated streak record
     */
    private static JsonNode updateStreakRecord(String userId,
                                               JsonNode latestEvent,
                                               JsonNode streakRecord,
                                               IQuestionAttemptManager questionAttemptManager)
            throws NoUserException, SegueDatabaseException {

        Long uId = Long.parseLong(userId);

        // timestamp of streak start
        Long streakStartTimestamp = streakRecord.path("streak_start").asLong();

        // timestamp of streak end
        Long streakEndTimestamp = streakRecord.path("streak_end").asLong();

        // timestamp of latest event
        Long latestEventTimestamp = latestEvent.path("timestamp").asLong();

        // set up calendar objects for date comparisons
        Calendar latest = Calendar.getInstance();
        latest.setTimeInMillis(latestEventTimestamp);
        latest = roundDownToDay(latest);

        // manual admin update of user streak
        if (latestEvent.path("event_type").asText().equals("ADMIN_UPDATE_USER_STREAK")) {

            Long newStreakLength = latestEvent.path("event_details").path("new_streak_length").asLong();

            // set latest event time to end of today
            latest.add(Calendar.DAY_OF_YEAR, 1);

            // get the "fake" start timestamp
            Long dummyStartTimestamp = latest.getTimeInMillis() - (86400000 * newStreakLength);

            ((ObjectNode) streakRecord).put("streak_start", dummyStartTimestamp);
            ((ObjectNode) streakRecord).put("streak_end", latest.getTimeInMillis());
            ((ObjectNode) streakRecord).put("current_activity", streakRecord.path("activity_threshold").asLong());

            if (newStreakLength > streakRecord.path("largest_streak").asLong()) {
                ((ObjectNode) streakRecord).put("largest_streak", newStreakLength);
            }

            return streakRecord;
        }


        // 1) If the current activity threshold for the day has been reached
        if (streakRecord.path("current_activity").asLong() == streakRecord.path("activity_threshold").asLong()) {

            // if we are still on the same day, don't continue, otherwise we need to reset the daily activity count
            if (latest.getTimeInMillis() < streakEndTimestamp) {
                return streakRecord;
            } else {
                ((ObjectNode) streakRecord).put("current_activity", 0);
            }
        }


        // 2) We want to make sure the user hasn't answered the question part correctly before. If they have, don't continue
        String questionPartId = latestEvent.path("event_details").path("questionId").asText();
        String questionPageId = questionPartId.split("\\|")[0];

        Map<String, Map<String, List<QuestionValidationResponse>>> userQuestionAttempts =
                questionAttemptManager.getQuestionAttemptsByUsersAndQuestionPrefix(ImmutableList.of(uId), ImmutableList.of(questionPageId)).get(uId);

        // the following assumes that question attempts are always recorded in the pg question attempt table before they are logged as an event
        if (!userQuestionAttempts.isEmpty()
                && userQuestionAttempts.containsKey(questionPageId)
                && userQuestionAttempts.get(questionPageId).containsKey(questionPartId)) {

            List<QuestionValidationResponse> correctResponses = Lists.newArrayList();
            for (QuestionValidationResponse response: userQuestionAttempts
                    .get(questionPageId)
                    .get(questionPartId)
                    ) {

                // make note of all correct attempts for this question part
                if (response.isCorrect()) {
                    correctResponses.add(response);
                }
            }

            // sort the attempts by date attempted
            correctResponses.sort(Comparator.comparing(QuestionValidationResponse::getDateAttempted));

            // only want to continue if the current correct attempt has never been processed before
            // need to accommodate for possibility of multiple correct attempts in pg question attempts table (e.g. when re-processing historical log events)
            if (correctResponses.size() > 1) {

                // dateAttempted is consistent across log event details and question attempt details, so compare this
                if (latestEvent.path("event_details").path("dateAttempted").asLong() > correctResponses.get(0).getDateAttempted().getTime()) {
                    return streakRecord;
                }
            }
        }


        // 3) If we have just started counting, or if the latest event arrived later than a day since the previous, reset the streak record and go no further
        if (streakStartTimestamp == 0 ||
                (((streakEndTimestamp != 0 && TimeUnit.DAYS.convert(latest.getTimeInMillis() - streakEndTimestamp, TimeUnit.MILLISECONDS) >= 1)))) {

            ((ObjectNode) streakRecord).put("streak_start", latest.getTimeInMillis());
            ((ObjectNode) streakRecord).put("streak_end", latest.getTimeInMillis());
            ((ObjectNode) streakRecord).put("current_activity", 0);
            streakStartTimestamp = latest.getTimeInMillis();
        }


        // 4) Increment the daily activity. If we've reached the daily activity threshold, increment the latest event time to the end of the day
        Long currentActivity = streakRecord.path("current_activity").asLong();
        ((ObjectNode) streakRecord).put("current_activity", currentActivity + 1);

        if (streakRecord.path("current_activity").asLong() == streakRecord.path("activity_threshold").asLong()) {
            latest.add(Calendar.DAY_OF_YEAR, 1);
            ((ObjectNode) streakRecord).put("streak_end", latest.getTimeInMillis());

            Long daysSinceStart = TimeUnit.DAYS.convert(latest.getTimeInMillis() - streakStartTimestamp, TimeUnit.MILLISECONDS);

            // log when the streak count has been updated
            Map<String, Object> eventDetailsStreakUpdate = Maps.newHashMap();
            eventDetailsStreakUpdate.put("currentStreak", daysSinceStart);
            eventDetailsStreakUpdate.put("threshold", streakRecord.path("activity_threshold").asLong());
            eventDetailsStreakUpdate.put("streakType", "correctQuestionPartsPerDay");


            // 5) Update largest streak count if days since start is greater than the recorded largest streak
            if (daysSinceStart > streakRecord.path("largest_streak").asLong()) {
                ((ObjectNode) streakRecord).put("largest_streak", daysSinceStart);

                // log the new longest streak record
                Map<String, Object> eventDetailsLongestStreak = Maps.newHashMap();
                eventDetailsLongestStreak.put("longestStreak", streakRecord.path("largest_streak").asLong());
                eventDetailsLongestStreak.put("threshold", streakRecord.path("activity_threshold").asLong());
                eventDetailsLongestStreak.put("streakType", "correctQuestionPartsPerDay");
            }


            // 6) At this point we want to notify the user that their streak has increased
            Map<String, Object> notificationData = Maps.newHashMap();
            Map<String, Object> streakData = Maps.newHashMap();
            streakData.put("currentStreak", daysSinceStart);
            streakData.put("currentActivity", streakRecord.path("current_activity").asInt());

            if (streakRecord.path("current_activity").asLong() == streakRecord.path("activity_threshold").asLong()) {
                streakData.put("streakIncrease", true);
            }

            notificationData.put("streakData", streakData);

            try {
                // notify the user of a streak increase
                IUserAlert alert = new PgUserAlert(null, uId,
                        objectMapper.writeValueAsString(notificationData),
                        "progress",
                        new Timestamp(System.currentTimeMillis()),
                        null, null, null);

                if (null != UserAlertsWebSocket.connectedSockets && UserAlertsWebSocket.connectedSockets.containsKey(uId)) {
                    for(IAlertListener listener : UserAlertsWebSocket.connectedSockets.get(uId)) {
                        listener.notifyAlert(alert);
                    }
                }
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }
        return streakRecord;
    }


    /**
     * Utility function to increase generic activity counts
     *
     * @param activityType the type of activity that we want to increment
     * @param record the holding record of the current snapshot
     * @return the new activity count
     */
    private static Integer updateActivityCount(String activityType, JsonNode record) {
        return record.path(activityType).asInt() + 1;
    }


    /**
     * Function to supply an initialized JsonNode record for storing non-student activity
     *
     * @return the initialized JsonNode record
     */
    private static JsonNode getInitializedNonStudentRecord() {

        return JsonNodeFactory.instance.objectNode()
                .put("groups_created", 0)
                .put("assignments_set", 0)
                .put("book_pages_set", 0)
                .put("cpd_events_attended", 0);
    }


    /**
     * Function to take a calendar object and round down the timestamp to midnight
     * for the same date
     *
     * @param calendar calendar object to modify
     * @return modified calendar object set to midnight
     */
    private static Calendar roundDownToDay(Calendar calendar) {

        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.HOUR_OF_DAY, 0);

        return calendar;
    }


    /**
     * Method to expose streams app details and status
     * TODO: we can share this between different streams apps in future if we introduce some inheritance between them
     * @return map of streams app properties
     */
    public static Map<String, Object> getAppStatus() {

        return ImmutableMap.of(
                "streamsApplicationName", streamsAppName,
                "version", streamsAppVersion,
                "running", streamThreadRunning);
    }
}
