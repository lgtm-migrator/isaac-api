package uk.ac.cam.cl.dtg.isaac.api;

import org.junit.Before;
import org.junit.Test;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import uk.ac.cam.cl.dtg.isaac.IsaacE2ETest;
import uk.ac.cam.cl.dtg.isaac.dto.IsaacEventPageDTO;
import uk.ac.cam.cl.dtg.isaac.dto.ResultsWrapper;
import uk.ac.cam.cl.dtg.isaac.dto.eventbookings.DetailedEventBookingDTO;
import uk.ac.cam.cl.dtg.isaac.dto.eventbookings.EventBookingDTO;
import uk.ac.cam.cl.dtg.isaac.dto.users.UserSummaryDTO;
import uk.ac.cam.cl.dtg.isaac.dto.users.UserSummaryWithEmailAddressDTO;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.AdditionalAuthenticationRequiredException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.AuthenticationProviderMappingException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.IncorrectCredentialsProvidedException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.MFARequiredButNotConfiguredException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoCredentialsAvailableException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoUserException;
import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertEquals;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

//@RunWith(PowerMockRunner.class)
@PowerMockIgnore("javax.net.ssl.*")
public class EventsFacadeTest extends IsaacE2ETest {

    private EventsFacade eventsFacade;

    @Before
    public void setUp() {
        // Get an instance of the facade to test
        eventsFacade = new EventsFacade(properties, logManager, eventBookingManager, userAccountManager, contentManager, "latest", userBadgeManager, userAssociationManager, groupManager, userAccountManager, schoolListReader, mapperFacade);
    }

    @Test
    public void getEventsTest() {
        HttpServletRequest request = createNiceMock(HttpServletRequest.class);
        expect(request.getCookies()).andReturn(new Cookie[]{}).anyTimes();
        replay(request);

        Response response = eventsFacade.getEvents(request, null, 0, 10, null, null, null, null, null, null);
        int status = response.getStatus();
        assertEquals(Response.Status.OK.getStatusCode(), status);
        Object entityObject = response.getEntity();
        assertNotNull(entityObject);
        @SuppressWarnings("unchecked") ResultsWrapper<IsaacEventPageDTO> entity = (ResultsWrapper<IsaacEventPageDTO>) entityObject;
        assertNotNull(entity);
        List<IsaacEventPageDTO> results = entity.getResults();
        assertNotNull(entity);
        assertEquals(7, results.size());
    }

    @Test
    public void getBookingByIdTest() throws NoCredentialsAvailableException, NoUserException, SegueDatabaseException, AuthenticationProviderMappingException, IncorrectCredentialsProvidedException, AdditionalAuthenticationRequiredException, InvalidKeySpecException, NoSuchAlgorithmException, MFARequiredButNotConfiguredException, SQLException {
        // --- Login as a student
        LoginResult studentLogin = loginAs(httpSession, properties.getProperty("TEST_STUDENT_EMAIL"), properties.getProperty("TEST_STUDENT_PASSWORD"));
        // --- Login as an event manager
        LoginResult eventManagerLogin = loginAs(httpSession, properties.getProperty("TEST_EVENTMANAGER_EMAIL"), properties.getProperty("TEST_EVENTMANAGER_PASSWORD"));

        // --- Create a booking as a logged in student
        HttpServletRequest createBookingRequest = createRequestWithCookies(new Cookie[] { studentLogin.cookie });
        replay(createBookingRequest);
        Response createBookingResponse = eventsFacade.createBookingForMe(createBookingRequest, "_regular_test_event", null);

        // Check that the booking was created successfully
        assertEquals(Response.Status.OK.getStatusCode(), createBookingResponse.getStatus());
        EventBookingDTO eventBookingDTO = null;
        if (null != createBookingResponse.getEntity() && createBookingResponse.getEntity() instanceof EventBookingDTO) {
            eventBookingDTO = (EventBookingDTO) createBookingResponse.getEntity();
            // Check that the returned entity is an EventBookingDTO and the ID of the user who created the booking matches
            assertEquals(studentLogin.user.getId(), ((EventBookingDTO) createBookingResponse.getEntity()).getUserBooked().getId());
        }
        assertNotNull(eventBookingDTO);

        // --- Check whether what we get as event managers contains the right amount of information
        HttpServletRequest getEventBookingsByIdRequest = createRequestWithCookies(new Cookie[] { eventManagerLogin.cookie });
        replay(getEventBookingsByIdRequest);

        Response getEventBookingsByIdResponse = eventsFacade.getEventBookingsById(getEventBookingsByIdRequest, eventBookingDTO.getBookingId().toString());
        assertNotNull(getEventBookingsByIdResponse.getEntity());
        assertEquals(DetailedEventBookingDTO.class.getCanonicalName(), getEventBookingsByIdResponse.getEntity().getClass().getCanonicalName());
        assertNotNull(((DetailedEventBookingDTO) getEventBookingsByIdResponse.getEntity()).getUserBooked());
        assertEquals(UserSummaryWithEmailAddressDTO.class.getCanonicalName(), ((EventBookingDTO) getEventBookingsByIdResponse.getEntity()).getUserBooked().getClass().getCanonicalName());

        // --- Delete the booking created above otherwise the other tests may be affected.
        HttpServletRequest cancelBookingRequest = createRequestWithCookies(new Cookie[] { studentLogin.cookie });
        replay(cancelBookingRequest);
        Response cancelBookingResponse = eventsFacade.cancelBooking(cancelBookingRequest, "_regular_test_event");
        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), cancelBookingResponse.getStatus());

        // --- Tear down
        // This should not be necessary, but we don't actually remove cancelled bookings from the database.
        PreparedStatement pst = postgresSqlDb.getDatabaseConnection().prepareStatement("DELETE FROM event_bookings WHERE id = ?;");
        pst.setLong(1, ((EventBookingDTO) createBookingResponse.getEntity()).getBookingId());
        pst.executeUpdate();
    }

    // events/{event_id}/bookings
    @Test
    public void getEventBookingsByEventIdTest() throws NoCredentialsAvailableException, NoUserException, SegueDatabaseException, AuthenticationProviderMappingException, IncorrectCredentialsProvidedException, AdditionalAuthenticationRequiredException, InvalidKeySpecException, NoSuchAlgorithmException, MFARequiredButNotConfiguredException {
        // Get event bookings by event id as an anonymous user (should fail)
        HttpServletRequest getEventBookingsAsAnonymous_Request = createNiceMock(HttpServletRequest.class);
        replay(getEventBookingsAsAnonymous_Request);
        Response getEventBookingsAsAnonymous_Response = eventsFacade.adminGetEventBookingByEventId(getEventBookingsAsAnonymous_Request, "_regular_test_event");
        assertNotEquals(Response.Status.OK.getStatusCode(), getEventBookingsAsAnonymous_Response.getStatus());

        // Get event bookings by event id as a student (should fail)
        LoginResult studentLogin = loginAs(httpSession, properties.getProperty("TEST_STUDENT_EMAIL"), properties.getProperty("TEST_STUDENT_PASSWORD"));
        HttpServletRequest getEventBookingsAsStudent_Request = createNiceMock(HttpServletRequest.class);
        expect(getEventBookingsAsStudent_Request.getCookies()).andReturn(new Cookie[] { studentLogin.cookie }).atLeastOnce();
        replay(getEventBookingsAsStudent_Request);
        Response getEventBookingsAsStudent_Response = eventsFacade.adminGetEventBookingByEventId(getEventBookingsAsStudent_Request, "_regular_test_event");
        assertNotEquals(Response.Status.OK.getStatusCode(), getEventBookingsAsStudent_Response.getStatus());

        // Get event bookings by event id as a teacher (should fail)
        LoginResult teacherLogin = loginAs(httpSession, properties.getProperty("TEST_TEACHER_EMAIL"), properties.getProperty("TEST_TEACHER_PASSWORD"));
        HttpServletRequest getEventBookingsAsTeacher_Request = createNiceMock(HttpServletRequest.class);
        expect(getEventBookingsAsTeacher_Request.getCookies()).andReturn(new Cookie[] { teacherLogin.cookie }).atLeastOnce();
        replay(getEventBookingsAsTeacher_Request);
        Response getEventBookingsAsTeacher_Response = eventsFacade.adminGetEventBookingByEventId(getEventBookingsAsTeacher_Request, "_regular_test_event");
        assertNotEquals(Response.Status.OK.getStatusCode(), getEventBookingsAsTeacher_Response.getStatus());

        // Get event bookings by event id as a teacher (should fail)
        LoginResult editorLogin = loginAs(httpSession, properties.getProperty("TEST_EDITOR_EMAIL"), properties.getProperty("TEST_EDITOR_PASSWORD"));
        HttpServletRequest getEventBookingsAsEditor_Request = createNiceMock(HttpServletRequest.class);
        expect(getEventBookingsAsEditor_Request.getCookies()).andReturn(new Cookie[] { editorLogin.cookie }).atLeastOnce();
        replay(getEventBookingsAsEditor_Request);
        Response getEventBookingsAsEditor_Response = eventsFacade.adminGetEventBookingByEventId(getEventBookingsAsEditor_Request, "_regular_test_event");
        assertNotEquals(Response.Status.OK.getStatusCode(), getEventBookingsAsEditor_Response.getStatus());

        // Get event bookings by event id as an event manager (should succeed)
        LoginResult eventManagerLogin = loginAs(httpSession, properties.getProperty("TEST_EVENTMANAGER_EMAIL"), properties.getProperty("TEST_EVENTMANAGER_PASSWORD"));
        HttpServletRequest getEventBookingsAsEventManager_Request = createNiceMock(HttpServletRequest.class);
        expect(getEventBookingsAsEventManager_Request.getCookies()).andReturn(new Cookie[] { eventManagerLogin.cookie }).atLeastOnce();
        replay(getEventBookingsAsEventManager_Request);
        Response getEventBookingsAsEventManager_Response = eventsFacade.adminGetEventBookingByEventId(getEventBookingsAsEventManager_Request, "_regular_test_event");
        assertEquals(Response.Status.OK.getStatusCode(), getEventBookingsAsEventManager_Response.getStatus());
        assertNotNull(getEventBookingsAsEventManager_Response.getEntity());
        assertTrue(getEventBookingsAsEventManager_Response.getEntity() instanceof List);
        List<?> entity = (List<?>) getEventBookingsAsEventManager_Response.getEntity();
        assertEquals(3, entity.size());
        for (Object o : entity) {
            assertEquals(DetailedEventBookingDTO.class.getCanonicalName(), o.getClass().getCanonicalName());
        }

        // Get event bookings by event id as an admin (should succeed)
        // NOTE: I was going to test as an admin too (same code as for Event Managers) but logging in with MFA is a
        // nightmare I'm not prepared to face yet. Plus, if we have people who obtained the ADMIN role, we have
        // bigger problems anyway.
    }

    @Test
    public void getEventBookingForAllGroupsTest() throws NoCredentialsAvailableException, NoUserException, SegueDatabaseException, AuthenticationProviderMappingException, IncorrectCredentialsProvidedException, AdditionalAuthenticationRequiredException, InvalidKeySpecException, NoSuchAlgorithmException, MFARequiredButNotConfiguredException {
        // Get event bookings by event id as an anonymous user (should fail)
        HttpServletRequest anonymous_Request = createNiceMock(HttpServletRequest.class);
        replay(anonymous_Request);
        Response anonymous_Response = eventsFacade.getEventBookingForAllGroups(anonymous_Request, "_regular_test_event");
        assertNotEquals(Response.Status.OK.getStatusCode(), anonymous_Response.getStatus());

        // Get event bookings by event id as a student (should fail)
        LoginResult studentLogin = loginAs(httpSession, properties.getProperty("TEST_STUDENT_EMAIL"), properties.getProperty("TEST_STUDENT_PASSWORD"));
        HttpServletRequest student_Request = createRequestWithCookies(new Cookie[] { studentLogin.cookie });
        replay(student_Request);
        Response student_Response = eventsFacade.getEventBookingForAllGroups(student_Request, "_regular_test_event");
        assertNotEquals(Response.Status.OK.getStatusCode(), student_Response.getStatus());

        LoginResult teacherLogin = loginAs(httpSession, properties.getProperty("TEST_TEACHER_EMAIL"), properties.getProperty("TEST_TEACHER_PASSWORD"));
        HttpServletRequest teacher_Request = createRequestWithCookies(new Cookie[] { teacherLogin.cookie });
        replay(teacher_Request);
        Response teacher_Response = eventsFacade.getEventBookingForAllGroups(teacher_Request, "_regular_test_event");
        assertEquals(Response.Status.OK.getStatusCode(), teacher_Response.getStatus());
        assertNotNull(teacher_Response.getEntity());
        // Make sure the EventBookingDTOs contain UserSummaryDTOs, thus not leaking information
        assertTrue(teacher_Response.getEntity() instanceof List);

        List<?> teacherEntity = (List<?>) teacher_Response.getEntity();
        for (Object o : teacherEntity) {
            assertEquals(EventBookingDTO.class.getCanonicalName(), o.getClass().getCanonicalName());
            assertEquals(UserSummaryDTO.class.getCanonicalName(), ((EventBookingDTO) o).getUserBooked().getClass().getCanonicalName());
        }
        Optional<UserSummaryDTO> teacherAlice = (Optional<UserSummaryDTO>) teacherEntity.stream().filter(e -> ((EventBookingDTO) e).getUserBooked().getId() == 7).findFirst();
        // Alice is associated with Teacher and is booked for this event => Alice should be present
        assertTrue(teacherAlice.isPresent());
        Optional<UserSummaryDTO> teacherCharlie = (Optional<UserSummaryDTO>) teacherEntity.stream().filter(e -> ((EventBookingDTO) e).getUserBooked().getId() == 9).findFirst();
        // Charlie is not associated with Teacher and is not booked for this event => Charlie should not be present
        assertFalse(teacherCharlie.isPresent());

        LoginResult daveLogin = loginAs(httpSession, "dave-teacher@test.com", properties.getProperty("TEST_TEACHER_PASSWORD"));
        HttpServletRequest dave_Request = createRequestWithCookies(new Cookie[] { daveLogin.cookie });
        replay(dave_Request);
        Response dave_Response = eventsFacade.getEventBookingForAllGroups(dave_Request, "_regular_test_event");
        assertEquals(Response.Status.OK.getStatusCode(), dave_Response.getStatus());
        assertNotNull(dave_Response.getEntity());
        // Make sure the EventBookingDTOs contain UserSummaryDTOs, thus not leaking information
        assertTrue(dave_Response.getEntity() instanceof List); // instanceof is OK here because we just need to know this is a subclass of a List
        List<?> daveEntity = (List<?>) dave_Response.getEntity();
        for (Object o : daveEntity) {
            assertEquals(EventBookingDTO.class.getCanonicalName(), o.getClass().getCanonicalName());
            assertEquals(UserSummaryDTO.class.getCanonicalName(), ((EventBookingDTO) o).getUserBooked().getClass().getCanonicalName());
        }
        Optional<UserSummaryDTO> daveAlice = (Optional<UserSummaryDTO>) daveEntity.stream().filter(e -> ((EventBookingDTO) e).getUserBooked().getId() == 7).findFirst();
        // Alice is not associated with Dave but is booked for this event => Alice should not be present
        assertFalse(daveAlice.isPresent());
        Optional<UserSummaryDTO> daveCharlie = (Optional<UserSummaryDTO>) daveEntity.stream().filter(e -> ((EventBookingDTO) e).getUserBooked().getId() == 9).findFirst();
        // Charlie is associated with Dave and is not booked for this event => Charlie should not be present
        assertFalse(daveCharlie.isPresent());
    }

    @Test
    public void getEventBookingsByEventIdForGroup() throws NoCredentialsAvailableException, NoUserException, SegueDatabaseException, AuthenticationProviderMappingException, IncorrectCredentialsProvidedException, AdditionalAuthenticationRequiredException, InvalidKeySpecException, NoSuchAlgorithmException, MFARequiredButNotConfiguredException {
        // Anonymous users MUST NOT be able to get event bookings by event id and group id
        HttpServletRequest anonymous_Request = createNiceMock(HttpServletRequest.class);
        replay(anonymous_Request);
        Response anonymous_Response = eventsFacade.getEventBookingForGivenGroup(anonymous_Request, "_regular_test_event", "1");
        assertNotEquals(Response.Status.OK.getStatusCode(), anonymous_Response.getStatus());

        // Teachers MUST be able to get event bookings by event id and group id if and only if they own the given group
        LoginResult teacherLogin = loginAs(httpSession, properties.getProperty("TEST_TEACHER_EMAIL"), properties.getProperty("TEST_TEACHER_PASSWORD"));
        HttpServletRequest teacher_Request = createRequestWithCookies(new Cookie[] { teacherLogin.cookie });
        replay(teacher_Request);
        Response teacher_Response = eventsFacade.getEventBookingForGivenGroup(teacher_Request, "_regular_test_event", "1");
        assertEquals(Response.Status.OK.getStatusCode(), teacher_Response.getStatus());
        List<?> teacherEntity = (List<?>) teacher_Response.getEntity();
        List<Long> bookedUserIds = teacherEntity.stream().map(booking -> ((EventBookingDTO)booking).getUserBooked().getId()).collect(Collectors.toList());
        assertTrue(bookedUserIds.containsAll(Arrays.asList(7L, 8L)));
        assertFalse(bookedUserIds.contains(9L)); // User 9 is booked but is not in Teacher's group.

        // Students MUST NOT be able to get event bookings by event id and group id
        LoginResult studentLogin = loginAs(httpSession, properties.getProperty("TEST_STUDENT_EMAIL"), properties.getProperty("TEST_STUDENT_PASSWORD"));
        HttpServletRequest student_Request = createRequestWithCookies(new Cookie[] { studentLogin.cookie });
        replay(student_Request);
        Response student_Response = eventsFacade.getEventBookingForGivenGroup(student_Request, "_regular_test_event", "2");
        // The student does not own the group so this should not succeed
        assertNotEquals(Response.Status.OK.getStatusCode(), student_Response.getStatus());

        // A student MUST NOT be able to get event bookings by event id and group id EVEN IF they belong in the group
        // Alice is part of group id 1
        LoginResult aliceLogin = loginAs(httpSession, "alice-student@test.com", properties.getProperty("TEST_STUDENT_PASSWORD"));
        HttpServletRequest alice_Request = createRequestWithCookies(new Cookie[] { aliceLogin.cookie });
        replay(alice_Request);
        Response alice_Response = eventsFacade.getEventBookingForGivenGroup(alice_Request, "_regular_test_event", "1");
        // The student does not own the group so this should not succeed
        assertNotEquals(Response.Status.OK.getStatusCode(), alice_Response.getStatus());

        LoginResult eventManagerLogin = loginAs(httpSession, properties.getProperty("TEST_EVENTMANAGER_EMAIL"), properties.getProperty("TEST_EVENTMANAGER_PASSWORD"));
        HttpServletRequest eventManager_Request = createRequestWithCookies(new Cookie[] { eventManagerLogin.cookie });
        replay(eventManager_Request);
        Response eventManager_Response = eventsFacade.getEventBookingForGivenGroup(eventManager_Request, "_regular_test_event", "2");
        // The event manager does not own the group so this should not succeed
        assertNotEquals(Response.Status.OK.getStatusCode(), eventManager_Response.getStatus());
    }
}
