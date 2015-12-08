/**
 * Copyright 2015 Alistair Stead
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
package uk.ac.cam.cl.dtg.segue.comm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;

import org.easymock.Capture;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.segue.api.Constants;
import uk.ac.cam.cl.dtg.segue.api.managers.ContentVersionController;
import uk.ac.cam.cl.dtg.segue.api.managers.UserManager;
import uk.ac.cam.cl.dtg.segue.auth.SegueLocalAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoUserException;
import uk.ac.cam.cl.dtg.segue.dao.ILogManager;
import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;
import uk.ac.cam.cl.dtg.segue.dao.content.ContentManagerException;
import uk.ac.cam.cl.dtg.segue.dao.content.IContentManager;
import uk.ac.cam.cl.dtg.segue.dos.AbstractEmailPreferenceManager;
import uk.ac.cam.cl.dtg.segue.dos.PgEmailPreferenceManager;
import uk.ac.cam.cl.dtg.segue.dos.users.RegisteredUser;
import uk.ac.cam.cl.dtg.segue.dto.content.ContentBaseDTO;
import uk.ac.cam.cl.dtg.segue.dto.content.ContentDTO;
import uk.ac.cam.cl.dtg.segue.dto.content.SeguePageDTO;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

/**
 * Test class for the EmailManager class.
 * 
 */
public class EmailManagerTest {
    private EmailCommunicator emailCommunicator;
    private RegisteredUser user;
    private RegisteredUserDTO userDTO;
    private static final Logger log = LoggerFactory.getLogger(EmailManagerTest.class);
    private EmailCommunicationMessage email = null;
    private PropertiesLoader mockPropertiesLoader;
    private ContentVersionController mockContentVersionController;
    private IContentManager mockContentManager;
    private Capture<EmailCommunicationMessage> capturedArgument;
    private SegueLocalAuthenticator mockAuthenticator;
    private AbstractEmailPreferenceManager emailPreferenceManager;
    private ILogManager logManager;
    private UserManager userManager;


    /**
     * Initial configuration of tests.
     * 
     * @throws Exception
     *             - test exception
     */
    @Before
    public final void setUp() throws Exception {


        // Create dummy user
        user = new RegisteredUser();
        user.setId(1L);
        user.setEmail("test@test.com");
        user.setGivenName("tester");
        user.setFamilyName("McTest");
        user.setResetToken("resetToken");
        user.setEmailVerificationToken("verificationToken");
        
        // Create dummy userDTO
        userDTO = new RegisteredUserDTO();
        userDTO.setId(1L);
        userDTO.setEmail("test@test.com");
        userDTO.setGivenName("tester");
        userDTO.setFamilyName("McTest");

        // Create dummy email communicator
        emailCommunicator = EasyMock.createMock(EmailCommunicator.class);
        
        // Create dummy email preferences
        emailPreferenceManager = EasyMock.createMock(PgEmailPreferenceManager.class);

        mockPropertiesLoader = EasyMock.createMock(PropertiesLoader.class);
        EasyMock.expect(mockPropertiesLoader.getProperty("HOST_NAME")).andReturn("dev.isaacphysics.org").anyTimes();
        EasyMock.expect(mockPropertiesLoader.getProperty("REPLY_TO_ADDRESS")).andReturn("test-reply@test.com")
                .anyTimes();

        EasyMock.replay(mockPropertiesLoader);

        mockContentVersionController = EasyMock.createMock(ContentVersionController.class);
        EasyMock.expect(mockContentVersionController.getLiveVersion()).andReturn("liveversion").anyTimes();

        // Create content manager
        mockContentManager = EasyMock.createMock(IContentManager.class);

        EasyMock.expect(mockContentVersionController.getContentManager()).andReturn(mockContentManager).anyTimes();
        EasyMock.replay(mockContentVersionController);
        
        // Create log manager
        logManager = EasyMock.createMock(ILogManager.class);
        logManager.logInternalEvent(null, null, null);
        EasyMock.expectLastCall().anyTimes();
        
        // Create user manager
        userManager = EasyMock.createMock(UserManager.class);

        capturedArgument = new Capture<EmailCommunicationMessage>();

        // Mock the emailCommunicator methods so we can see what is sent
        try {
            emailCommunicator.sendMessage(EasyMock.and(EasyMock.capture(capturedArgument),
                    EasyMock.isA(EmailCommunicationMessage.class)));
        } catch (CommunicationException e1) {
            e1.printStackTrace();
            Assert.fail();
        }

        
        EasyMock.replay(emailCommunicator);
        System.out.println("setup");
        
        mockAuthenticator = EasyMock.createMock(SegueLocalAuthenticator.class);
        
        EasyMock.expect(mockAuthenticator.createEmailVerificationTokenForUser(user, user.getEmail())).andAnswer(
                new IAnswer<RegisteredUser>() {

                    @Override
                    public RegisteredUser answer() throws Throwable {
                        user.setEmailVerificationToken("emailVerificationToken");
                        return user;
                    }
            
                });


        EasyMock.replay(mockAuthenticator);
    }

    /**
     * @param template - id of the template
     * @return - SegueDTO object
     */
    public SeguePageDTO createDummyEmailTemplate(final String template) {

        ArrayList<ContentBaseDTO> children = new ArrayList<ContentBaseDTO>();

        ContentDTO child = new ContentDTO(null, null, "content", null, null, null, 
                null, null, null, null, template, null, null, null, null, 0);

        children.add(child);

        SeguePageDTO seguePage = new SeguePageDTO("01234",
                "email-template-registration-confirmation", "title", "subtitle", "page", "ags46", "markdown",
                "canonical-source-file", null, children, null, null, null, true, null, 0);
        
        return seguePage;
    }

    /**
     * Verifies that email templates are parsed and replaced correctly.
     * 
     * @throws CommunicationException
     */
    @Test
    public final void sendRegistrationConfirmation_checkForTemplateCompletion_emailShouldBeSentWithTemplateTagsFilledIn() {
        

        EasyMock.replay(userManager);
    	
        SeguePageDTO template = createDummyEmailTemplate("Hi, {{givenname}}."
                + "\nThanks for registering!\nYour Isaac email address is: "
                + "</a href='mailto:{{email}}'>{{email}}<a>.\naddress</a>\n{{sig}}");

        SeguePageDTO htmlTemplate = createDummyEmailTemplate("<!DOCTYPE html><html><head><meta charset='utf-8'>"
		        		+ "<title>Isaac Physics project</title></head><body>"
		                + "{{content}}"
		                + "</body></html>");
        
        SeguePageDTO asciiTemplate = createDummyEmailTemplate("{{content}}");
        try {
            EasyMock.expect(
                    mockContentManager.getContentById("liveversion", "email-template-registration-confirmation"))
                    .andReturn(template);

            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-html")).andReturn(htmlTemplate);
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-ascii")).andReturn(asciiTemplate);
            EasyMock.replay(mockContentManager);

        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        }


        EmailManager manager = new EmailManager(emailCommunicator, emailPreferenceManager, 
        				mockPropertiesLoader, mockContentVersionController, logManager);
        try {
            manager.sendRegistrationConfirmation(userDTO, user.getEmailVerificationToken());
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (SegueDatabaseException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (NoUserException e) {
            e.printStackTrace();
            Assert.fail();
		}

        final String expectedMessagePlainText = "Hi, tester.\nThanks for registering!\nYour Isaac email address is: "
                + "</a href='mailto:test@test.com'>test@test.com<a>.\n" + "address</a>\nIsaac Physics Project";
        
        final String expectedMessageHTML = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>Isaac "
		        		+ "Physics project</title></head><body>"
		                + "Hi, tester.<br>Thanks for registering!<br>Your Isaac email address is: "
		                + "</a href='mailto:test@test.com'>test@test.com<a>.<br>" + "address</a><br>Isaac "
                		+ "Physics Project</body></html>";

        // Wait for the emailQueue to spin up and send our message
        int i = 0;
        while (!capturedArgument.hasCaptured() && i < 5) {
            try {
                Thread.sleep(100);
                i++;
            } catch (InterruptedException e) {
                e.printStackTrace();
                Assert.fail();
            }
        }
        email = capturedArgument.getValue();
        assertNotNull(email);
        assertEquals(expectedMessagePlainText, email.getPlainTextMessage());
        assertEquals(expectedMessageHTML, email.getHTMLMessage());
        System.out.println(email.getPlainTextMessage());
    }

    /**
     * Verifies that email templates are parsed and replaced correctly.
     * 
     * @throws CommunicationException
     */
    @Test
    public final void sendFederatedPasswordReset_checkForTemplateCompletion_emailShouldBeSentWithTemplateTagsFilledIn() {

        SeguePageDTO template = createDummyEmailTemplate("Hello, {{givenname}}.\n\nYou requested a "
                + "password reset. However you use {{providerString}} to log in to our site. You need"
                + " to go to your authentication {{providerWord}} to reset your password.\n\nRegards,\n\n{{sig}}");
        
        SeguePageDTO htmlTemplate = createDummyEmailTemplate("{{content}}");
        try {
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-federated-password-reset")).andReturn(template);
            
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-html")).andReturn(htmlTemplate);
            
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-ascii")).andReturn(htmlTemplate);

            EasyMock.replay(mockContentManager);
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        }

        EmailManager manager = new EmailManager(emailCommunicator, emailPreferenceManager, mockPropertiesLoader, 
        				mockContentVersionController, logManager);
        try {
            manager.sendFederatedPasswordReset(userDTO, "testString", "testWord");
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
            log.debug(e.getMessage());
        } catch (SegueDatabaseException e) {
            e.printStackTrace();
            log.debug(e.getMessage());
            Assert.fail();
        } catch (NoUserException e) {
            e.printStackTrace();
            log.debug(e.getMessage());
            Assert.fail();
		}

        final String expectedMessage = "Hello, tester.\n\nYou requested a password reset. "
                + "However you use testString to log in to our site. You need to go to your "
                + "authentication testWord to reset your password.\n\nRegards,\n\nIsaac Physics Project";

        // Wait for the emailQueue to spin up and send our message
        int i = 0;
        while (!capturedArgument.hasCaptured() && i < 5) {
            try {
                Thread.sleep(100);
                i++;
            } catch (InterruptedException e) {
                e.printStackTrace();
                Assert.fail();
            }
        }
        email = capturedArgument.getValue();
        assertNotNull(email);
        assertEquals(expectedMessage, email.getPlainTextMessage());
        System.out.println(email.getPlainTextMessage());

    }

    /**
     * Verifies that email templates are parsed and replaced correctly.
     * 
     * @throws CommunicationException
     */
    @Test
    public final void sendPasswordReset_checkForTemplateCompletion_emailShouldBeSentWithTemplateTagsFilledIn() {

        SeguePageDTO template = createDummyEmailTemplate("Hello, {{givenname}}.\n\nA request has been "
                + "made to reset the password for the account: </a href='mailto:{{email}}'>{{email}}<a>"
                + ".\n\nTo reset your password <a href='{{resetURL}}'>Click Here</a>\n\nRegards,\n\n{{sig}}");
        
        SeguePageDTO htmlTemplate = createDummyEmailTemplate("{{content}}");
        

        try {
            EasyMock.expect(mockContentManager.getContentById("liveversion", "email-template-password-reset"))
                    .andReturn(template).once();
            
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-html")).andReturn(htmlTemplate).once();
            
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-ascii")).andReturn(htmlTemplate);

            EasyMock.replay(mockContentManager);
            
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        }

        EmailManager manager = new EmailManager(emailCommunicator, emailPreferenceManager ,mockPropertiesLoader, 
        				mockContentVersionController, logManager);
        try {
            manager.sendPasswordReset(userDTO, user.getResetToken());
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (SegueDatabaseException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (NoUserException e) {
            e.printStackTrace();
            Assert.fail();
		}

        final String expectedMessage = "Hello, tester.\n\nA request has been "
                + "made to reset the password for the account: </a href='mailto:test@test.com'>test@test.com<a>"
                + ".\n\nTo reset your password <a href='https://dev.isaacphysics.org/resetpassword/resetToken'>"
                + "Click Here</a>\n\nRegards,\n\nIsaac Physics Project";

        // Wait for the emailQueue to spin up and send our message
        int i = 0;
        while (!capturedArgument.hasCaptured() && i < 5) {
            try {
                Thread.sleep(100);
                i++;
            } catch (InterruptedException e) {
                e.printStackTrace();
                Assert.fail();
            }
        }
        email = capturedArgument.getValue();
        assertNotNull(email);
        assertEquals(expectedMessage, email.getPlainTextMessage());
    }

    /**
     * Verify that if there are extra tags the system doesn't recognise, there will be an exception, and the email won't
     * be sent.
     * 
     * @throws CommunicationException
     */
    @Test(expected = IllegalArgumentException.class)
    public final void sendRegistrationConfirmation_checkForInvalidTemplateTags_throwIllegalArgumentException() {
        SeguePageDTO template = createDummyEmailTemplate("Hi, {{givenname}} {{surname}}.\n"
                + "Thanks for registering!\nYour Isaac email address is: "
                + "</a href='mailto:{{email}}'>{{email}}<a>.\naddress</a>\n{{sig}}");

        SeguePageDTO htmlTemplate = createDummyEmailTemplate("{{content}}");
        // Create content manager
        try {
            EasyMock.expect(
                    mockContentManager.getContentById("liveversion", "email-template-registration-confirmation"))
                    .andReturn(template).once();
         
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-html")).andReturn(htmlTemplate).once();
            
            EasyMock.replay(mockContentManager);

        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        }


        EmailManager manager = new EmailManager(emailCommunicator, emailPreferenceManager, mockPropertiesLoader,
        				mockContentVersionController, logManager);
        try {
            manager.sendRegistrationConfirmation(userDTO, user.getEmailVerificationToken());
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (SegueDatabaseException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (NoUserException e) {
            e.printStackTrace();
            Assert.fail();
		}
    }

    /**
     * Verify that the system responds correctly when there are fewer tags than expected in the email template.
     */
    @Test
    public final void sendRegistrationConfirmation_checkTemplatesWithNoTagsWorks_emailIsGeneratedWithoutTemplateContent() {
        SeguePageDTO template = createDummyEmailTemplate("this is a template with no tags");

        PropertiesLoader mockPropertiesLoader = EasyMock.createMock(PropertiesLoader.class);
        EasyMock.expect(mockPropertiesLoader.getProperty("HOST_NAME")).andReturn("dev.isaacphysics.org");

        EasyMock.expect(mockPropertiesLoader.getProperty(Constants.REPLY_TO_ADDRESS)).andReturn(
                "test-reply-to@test.com").anyTimes();

        EasyMock.replay(mockPropertiesLoader);

        ContentVersionController mockContentVersionController = EasyMock.createMock(ContentVersionController.class);
        EasyMock.expect(mockContentVersionController.getLiveVersion()).andReturn("liveversion").anyTimes();

        // Create content manager
        IContentManager mockContentManager = EasyMock.createMock(IContentManager.class);
        
        SeguePageDTO htmlTemplate = createDummyEmailTemplate("{{content}}");
        try {
            EasyMock.expect(
                    mockContentManager.getContentById("liveversion", "email-template-registration-confirmation"))
                    .andReturn(template);
            
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-ascii")).andReturn(htmlTemplate);
            
            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-html")).andReturn(htmlTemplate);

            EasyMock.replay(mockContentManager);
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        }
        EasyMock.expect(mockContentVersionController.getContentManager()).andReturn(mockContentManager).anyTimes();
        
        EasyMock.replay(mockContentVersionController);


        EmailManager manager = new EmailManager(emailCommunicator, emailPreferenceManager, mockPropertiesLoader, 
        				mockContentVersionController, logManager);
        try {
            manager.sendRegistrationConfirmation(userDTO, user.getEmailVerificationToken());
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (SegueDatabaseException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (NoUserException e) {
            e.printStackTrace();
            Assert.fail();
		}

        // Wait for the emailQueue to spin up and send our message
        int i = 0;
        while (!capturedArgument.hasCaptured() && i < 5) {
            try {
                Thread.sleep(100);
                i++;
            } catch (InterruptedException e) {
                e.printStackTrace();
                Assert.fail();
            }
        }
        email = capturedArgument.getValue();
        assertNotNull(email);
        assertEquals("this is a template with no tags", email.getPlainTextMessage());
        System.out.println(email.getPlainTextMessage());
    }
    
    
    /**
     * Make sure that when the templates are published:false, that the method reacts appropriately. 
     */
    @Test 
    public void sendRegistrationConfirmation_checkNullContentDTO_exceptionThrownAndDealtWith(){
    	SeguePageDTO htmlTemplate = createDummyEmailTemplate("{{content}}");
        try {
            EasyMock.expect(
                    mockContentManager.getContentById("liveversion", "email-template-registration-confirmation"))
                    .andReturn(null);

            EasyMock.expect(mockContentManager.getContentById("liveversion", 
                    "email-template-html")).andReturn(htmlTemplate);

            EasyMock.replay(mockContentManager);
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        }


        EmailManager manager = new EmailManager(emailCommunicator, emailPreferenceManager, 
        				mockPropertiesLoader, mockContentVersionController, logManager);
        try {
            manager.sendRegistrationConfirmation(userDTO, user.getEmailVerificationToken());
        } catch (ContentManagerException e) {
            e.printStackTrace();
            Assert.fail();
        } catch (SegueDatabaseException e) {
            e.printStackTrace();
            log.info(e.getMessage());
        } catch (NoUserException e) {
            e.printStackTrace();
            log.info(e.getMessage());
		}


        // Wait for the emailQueue to spin up and send our message (if it exists)
        int i = 0;
        while (!capturedArgument.hasCaptured() && i < 5) {
            try {
                Thread.sleep(100);
                i++;
            } catch (InterruptedException e) {
                e.printStackTrace();
                Assert.fail();
            }
        }
        // We expect there to be nothing captured because the content was not returned
        assertFalse(capturedArgument.hasCaptured());
    }
}