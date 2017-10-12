/**
 * Copyright 2014 Stephen Cummins & Nick Rogers.
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

import static uk.ac.cam.cl.dtg.segue.api.Constants.*;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ma.glasnost.orika.MapperFacade;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.segue.api.Constants;
import uk.ac.cam.cl.dtg.segue.auth.AuthenticationProvider;
import uk.ac.cam.cl.dtg.segue.auth.IAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.IFederatedAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.IOAuth1Authenticator;
import uk.ac.cam.cl.dtg.segue.auth.IOAuth2Authenticator;
import uk.ac.cam.cl.dtg.segue.auth.IOAuthAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.IPasswordAuthenticator;
import uk.ac.cam.cl.dtg.segue.auth.OAuth1Token;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.AuthenticationCodeException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.AuthenticationProviderMappingException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.AuthenticatorSecurityException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.CodeExchangeException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.CrossSiteRequestForgeryException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.IncorrectCredentialsProvidedException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.InvalidPasswordException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.InvalidSessionException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.InvalidTokenException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.MissingRequiredFieldException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoCredentialsAvailableException;
import uk.ac.cam.cl.dtg.segue.auth.exceptions.NoUserException;
import uk.ac.cam.cl.dtg.segue.comm.CommunicationException;
import uk.ac.cam.cl.dtg.segue.comm.EmailManager;
import uk.ac.cam.cl.dtg.segue.comm.EmailType;
import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;
import uk.ac.cam.cl.dtg.segue.dao.content.ContentManagerException;
import uk.ac.cam.cl.dtg.segue.dao.users.IUserDataManager;
import uk.ac.cam.cl.dtg.segue.dos.users.RegisteredUser;
import uk.ac.cam.cl.dtg.segue.dos.users.UserFromAuthProvider;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;
import uk.ac.cam.cl.dtg.util.PropertiesLoader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;

/**
 * This class handles all authentication details, including creation / destruction of sessions. It also handles adding
 * or removing authorised third party providers to Segue Accounts and dealing with password resets.
 */
public class UserAuthenticationManager {
    private static final Logger log = LoggerFactory.getLogger(UserAuthenticationManager.class);
    private static final String HMAC_SHA_ALGORITHM = "HmacSHA256";

    private final PropertiesLoader properties;
    private final IUserDataManager database;
    private final EmailManager emailManager;
    private final ObjectMapper serializationMapper;
    
    private final Map<AuthenticationProvider, IAuthenticator> registeredAuthProviders;
    
    /**
     * Fully injectable constructor.
     * 
     * @param database
     *            - an IUserDataManager that will support persistence.
     * @param properties
     *            - A property loader
     * @param providersToRegister
     *            - A map of known authentication providers.
     * @param dtoMapper
     *            - the preconfigured DO to DTO object mapper for user objects.
     * @param emailQueue
     *            - the preconfigured communicator manager for sending e-mails.
     */
    @Inject
    public UserAuthenticationManager(final IUserDataManager database,
            final PropertiesLoader properties, final Map<AuthenticationProvider, IAuthenticator> providersToRegister,
            final MapperFacade dtoMapper, final EmailManager emailQueue) {
        Validate.notNull(properties.getProperty(HMAC_SALT));
        Validate.notNull(Integer.parseInt(properties.getProperty(SESSION_EXPIRY_SECONDS)));
        Validate.notNull(properties.getProperty(HOST_NAME));

        this.database = database;
       
        this.properties = properties;

        this.registeredAuthProviders = providersToRegister;

        this.emailManager = emailQueue;
        this.serializationMapper = new ObjectMapper();
    }

    /**
     * This method will trigger the authentication flow for a 3rd party authenticator.
     * 
     * This method can be used for regular logins, new registrations or for linking 3rd party authenticators to an
     * existing Segue user account.
     * 
     * @param request
     *            - http request that we can attach the session to and that already has a redirect url attached.
     * @param provider
     *            - the provider the user wishes to authenticate with.
     * @return A json response containing a URI to the authentication provider if authorization / login is required.
     *         Alternatively a SegueErrorResponse could be returned.
     * @throws IOException - 
     * @throws AuthenticationProviderMappingException - as per exception description.
     */
    public URI getThirdPartyAuthURI(final HttpServletRequest request, final String provider) 
            throws IOException, AuthenticationProviderMappingException {
        IAuthenticator federatedAuthenticator = mapToProvider(provider);

        // if we are an OAuthProvider redirect to the provider
        // authorisation URL.
        URI redirectLink = null;
        if (federatedAuthenticator instanceof IOAuth2Authenticator) {
            IOAuth2Authenticator oauth2Provider = (IOAuth2Authenticator) federatedAuthenticator;
            String antiForgeryTokenFromProvider = oauth2Provider.getAntiForgeryStateToken();

            // Store antiForgeryToken in the users session.
            request.getSession().setAttribute(STATE_PARAM_NAME, antiForgeryTokenFromProvider);

            redirectLink = URI.create(oauth2Provider.getAuthorizationUrl(antiForgeryTokenFromProvider));
        } else if (federatedAuthenticator instanceof IOAuth1Authenticator) {
            IOAuth1Authenticator oauth1Provider = (IOAuth1Authenticator) federatedAuthenticator;
            OAuth1Token token = oauth1Provider.getRequestToken();

            // Store token and secret in the users session.
            request.getSession().setAttribute(OAUTH_TOKEN_PARAM_NAME, token.getToken());

            redirectLink = URI.create(oauth1Provider.getAuthorizationUrl(token));
        } else {
            throw new AuthenticationProviderMappingException("Unable to map to a known authenticator. "
                    + "The provider: " + provider + " is unknown");
        }

        return redirectLink;
    }

    /**
     * Get the 3rd party authentication providers user object.
     * This can be used to look up existing segue users or create a new one.
     * 
     * @param request
     *            - to retrieve session params
     * @param provider
     *            - the provider we are interested in.
     * @return a user object with 3rd party data inside.
     * @throws AuthenticationProviderMappingException
     *             - if we cannot locate an appropriate authenticator.
     * @throws SegueDatabaseException
     *             - if there is a local database error.
     * @throws IOException
     *             - Problem reading something
     * @throws NoUserException
     *             - If the user doesn't exist with the provider.
     * @throws AuthenticatorSecurityException
     *             - If there is a security probably with the authenticator.
     * @throws CrossSiteRequestForgeryException
     *             - as per exception description.
     * @throws CodeExchangeException
     *             - as per exception description.
     * @throws AuthenticationCodeException
     *             - as per exception description.
     */
    public UserFromAuthProvider getThirdPartyUserInformation(final HttpServletRequest request, final String provider)
            throws AuthenticationProviderMappingException, AuthenticatorSecurityException, NoUserException,
            IOException, SegueDatabaseException, AuthenticationCodeException, CodeExchangeException,
            CrossSiteRequestForgeryException {
        IAuthenticator authenticator = mapToProvider(provider);

        IOAuthAuthenticator oauthProvider;

        // this is a reference that the provider can use to look up user details.
        String providerSpecificUserLookupReference = null;

        // if we are an OAuth2Provider complete next steps of oauth
        if (authenticator instanceof IOAuthAuthenticator) {
            oauthProvider = (IOAuthAuthenticator) authenticator;

            providerSpecificUserLookupReference = this.getOauthInternalRefCode(oauthProvider, request);
        } else {
            throw new AuthenticationProviderMappingException("Unable to map to a known authenticator. The provider: "
                    + provider + " is unknown");
        }

        UserFromAuthProvider userFromProvider = oauthProvider.getUserInfo(providerSpecificUserLookupReference);
        return userFromProvider;
    }

    /**
     * This method will attempt to find a segue user using a 3rd party provider and a unique id that identifies the user
     * to the provider.
     * 
     * @param provider
     *            - the provider that we originally validated with
     * @param providerId
     *            - the unique ID of the user as given to us from the provider.
     * @return A user object or null if we were unable to find the user with the information provided.
     * @throws SegueDatabaseException
     *             - If there is an internal database error.
     */
    public RegisteredUser getSegueUserFromLinkedAccount(final AuthenticationProvider provider, final String providerId)
            throws SegueDatabaseException {
        Validate.notNull(provider);
        Validate.notBlank(providerId);

        RegisteredUser user = database.getByLinkedAccount(provider, providerId);
        if (null == user) {
            log.debug("Unable to locate user based on provider " + "information provided.");
        }
        return user;
    }
    
    /**
     * @param provider
     *            - the provider the user wishes to authenticate with.
     * @param email
     *            - the email the user wishes to use
     * @param plainTextPassword
     *            - the plain text password the user has provided
     * @return
     *            - a registered user object
     * @throws AuthenticationProviderMappingException
     *             - if we cannot find an authenticator
     * @throws SegueDatabaseException
     *             - if there is a problem with the database.
     * @throws IncorrectCredentialsProvidedException
     *             - if the password is incorrect
     * @throws NoUserException
     *             - if the user does not exist
     * @throws NoCredentialsAvailableException
     *             - If the account exists but does not have a local password
     */
    public final RegisteredUser getSegueUserFromCredentials(final String provider, final String email, 
                        final String plainTextPassword) throws AuthenticationProviderMappingException, 
                        SegueDatabaseException, IncorrectCredentialsProvidedException, NoUserException, 
                        NoCredentialsAvailableException {
        Validate.notBlank(email);
        Validate.notNull(plainTextPassword);
        IAuthenticator authenticator = mapToProvider(provider);
        
        if (authenticator instanceof IPasswordAuthenticator) {
            IPasswordAuthenticator passwordAuthenticator = (IPasswordAuthenticator) authenticator;
            
            return passwordAuthenticator.authenticate(email, plainTextPassword);
        } else {
            throw new AuthenticationProviderMappingException("Unable to map to a known authenticator that accepts "
                 + "raw credentials for the given provider: " + provider);
        }
    }

    /**
     * Checks to see if a user has valid way to authenticate with Segue.
     *
     * @param user - to check
     * @return true means the user should have a means of authenticating with their account as far as we are concerned
     */
    public boolean hasLocalCredentials(RegisteredUser user) throws SegueDatabaseException {
        IPasswordAuthenticator passwordAuthenticator = (IPasswordAuthenticator) this.registeredAuthProviders
                .get(AuthenticationProvider.SEGUE);

        return passwordAuthenticator.hasPasswordRegistered(user);
    }
    
    /**
     * This method will look up a userDO based on the session information provided.
     * @param request containing session information
     * @return either a user or null if we couldn't find the user for whatever reason.
     */
    public RegisteredUser getUserFromSession(final HttpServletRequest request) {
        Validate.notNull(request);

        Map<String, String> currentSessionInformation;
        try {
            currentSessionInformation = this.getSegueSessionFromRequest(request);
        } catch (IOException e1) {
            log.error("Error parsing session information to retrieve user.");
            return null;
        } catch (InvalidSessionException e) {
            log.debug("We cannot read the session information. It probably doesn't exist");
            // assuming that no user is logged in.
            return null;
        }

        // Check if the request originated from Isaac:
        String referrer = request.getHeader("Referer");  // Note HTTP Header misspelling!
        if (null == referrer) {
            log.warn("Authenticated request had no 'Referer' information set!");
        } else if (!referrer.startsWith("https://" + Constants.HOST_NAME)) {
            log.warn("Authenticated request had non-Isaac 'Referer': '" + referrer + "'");
        }
        String origin = request.getHeader("Origin");
        boolean isPostRequest = "POST".equals(request.getMethod());
        if (isPostRequest && null == origin) {
            log.warn("Authenticated POST request had no 'Origin' information!");
        } else if (isPostRequest && !origin.startsWith("https://" + Constants.HOST_NAME)) {
            log.warn("Authenticated request had non-Isaac 'Origin': '" + origin + "'");
        }

        // check if the users session is valid.
        if (!this.isValidUsersSession(currentSessionInformation)) {
            log.debug("User session has failed validation. Assume they are not logged in. Session: "
                    + currentSessionInformation);
            return null;
        }

        // retrieve the user from database.
        try {
            // get the current user based on their session id information
            Long currentUserId = Long.parseLong(currentSessionInformation.get(SESSION_USER_ID));
            
            // should be ok as isValidUser checks this.
            Validate.notNull(currentUserId);
            
            return database.getById(currentUserId);
        } catch (SegueDatabaseException e) {
            log.error("Internal Database error. Failed to resolve current user.", e);
            return null;
        } catch (NumberFormatException e) {
            log.info("Invalid user id detected in session. " + currentSessionInformation.get(SESSION_USER_ID));
            return null;            
        }
    }
    
    /**
     * Create a signed session based on the user DO provided and the http request and response.
     * @param request - for creating the session
     * @param response - for creating the session
     * @param user - the user who should be logged in.
     * @return the request and response will be modified and the original userDO will be returned for convenience.
     */
    public RegisteredUser createUserSession(final HttpServletRequest request, final HttpServletResponse response,
            final RegisteredUser user) {
        this.createSession(request, response, user);
        return user;
    }
    
    /**
     * Destroy a session attached to the request.
     * 
     * @param request
     *            containing the tomcat session to destroy
     * @param response
     *            to destroy the segue cookie.
     */
    public void destroyUserSession(final HttpServletRequest request, final HttpServletResponse response) {
        Validate.notNull(request);
        try {
            request.getSession().invalidate();
            Cookie logoutCookie = new Cookie(SEGUE_AUTH_COOKIE, "");
            logoutCookie.setPath("/");
            logoutCookie.setMaxAge(0);
            logoutCookie.setHttpOnly(true);

            response.addCookie(logoutCookie);
        } catch (IllegalStateException e) {
            log.info("The session has already been invalidated. " + "Unable to logout again...", e);
        }
    }
    
    /**
     * Attempts to map a string to a known provider.
     * 
     * @param provider
     *            - String representation of the provider requested
     * @return the FederatedAuthenticator object which can be used to get a user.
     * @throws AuthenticationProviderMappingException
     *             if we are unable to locate the provider requested.
     */
    public IAuthenticator mapToProvider(final String provider) throws AuthenticationProviderMappingException {
        Validate.notEmpty(provider, "Provider name must not be empty or null if we are going "
                + "to map it to an implementation.");

        AuthenticationProvider enumProvider = null;
        try {
            enumProvider = AuthenticationProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AuthenticationProviderMappingException("The provider requested is "
                    + "invalid and not a known AuthenticationProvider: " + provider);
        }

        if (!registeredAuthProviders.containsKey(enumProvider)) {
            throw new AuthenticationProviderMappingException("This authentication provider"
                    + " has not been registered / implemented yet: " + provider);
        }

        log.debug("Mapping provider: " + provider + " to " + enumProvider);

        return this.registeredAuthProviders.get(enumProvider);
    }
    
    /**
     * Link Provider To Existing Account.
     * 
     * @param currentUser
     *            - the current user to link provider to.
     * @param federatedAuthenticator
     *            the federatedAuthenticator we are using for authentication
     * @param providerUserObject
     *            - the user object provided by the 3rd party authenticator.
     * 
     * @throws AuthenticatorSecurityException
     *             - If a third party authenticator fails a security check.
     * @throws NoUserException
     *             - If we are unable to find a user that matches
     * @throws IOException
     *             - If there is a problem reading from the data source.
     * @throws SegueDatabaseException
     *             - If there is an internal database error.
     */
    public void linkProviderToExistingAccount(final RegisteredUser currentUser,
            final AuthenticationProvider federatedAuthenticator, final UserFromAuthProvider providerUserObject)
            throws AuthenticatorSecurityException, NoUserException, IOException, SegueDatabaseException {
        Validate.notNull(currentUser);
        Validate.notNull(federatedAuthenticator);
        Validate.notNull(providerUserObject);

        this.database.linkAuthProviderToAccount(currentUser, federatedAuthenticator,
                providerUserObject.getProviderUserId());
    }
    
    /**
     * Unlink User From AuthenticationProvider
     * 
     * Removes the link between a user and a provider.
     * 
     * @param userDO
     *            - user to affect.
     * @param providerString
     *            - provider to unassociated.
     * @throws SegueDatabaseException
     *             - if there is an error during the database update.
     * @throws MissingRequiredFieldException
     *             - If the change will mean that the user will be unable to login again.
     * @throws AuthenticationProviderMappingException
     *             - if we are unable to locate the authentication provider specified.
     */
    public void unlinkUserAndProvider(final RegisteredUser userDO, final String providerString)
            throws SegueDatabaseException, MissingRequiredFieldException, AuthenticationProviderMappingException {

        // check if the provider is there to delete in the first place. If not just return.
        if (!this.database.getAuthenticationProvidersByUser(userDO).contains(
                this.mapToProvider(providerString).getAuthenticationProvider())) {
            return;
        }

        IPasswordAuthenticator authenticator = (IPasswordAuthenticator) this
                    .mapToProvider(AuthenticationProvider.SEGUE.name());

        // make sure that the change doesn't prevent the user from logging in again.
        if ((this.database.getAuthenticationProvidersByUser(userDO).size() > 1) || authenticator.hasPasswordRegistered(userDO)) {
            this.database.unlinkAuthProviderFromUser(userDO, this.mapToProvider(providerString)
                    .getAuthenticationProvider());
        } else {
            throw new MissingRequiredFieldException("This modification would mean that the user"
                    + " no longer has a way of authenticating. Failing change.");
        }
    }
    
    
    /**
     * This method will use an email address to check a local user exists and if so, will send an email with a unique
     * token to allow a password reset. This method does not indicate whether or not the email actually existed.
     *
     * @param userDO
     *            - A user object containing the email address of the user to reset the password for.
     * @param userAsDTO
     *            - A user DTO object sanitised so that we can send it to the email manager.
     * @throws NoSuchAlgorithmException
     *             - if the configured algorithm is not valid.
     * @throws InvalidKeySpecException
     *             - if the preconfigured key spec is invalid.
     * @throws CommunicationException
     *             - if a fault occurred whilst sending the communique
     * @throws SegueDatabaseException
     *             - If there is an internal database error.
     */
    public final void resetPasswordRequest(final RegisteredUser userDO, final RegisteredUserDTO userAsDTO)
            throws InvalidKeySpecException,
            NoSuchAlgorithmException, CommunicationException, SegueDatabaseException {
        try {
            IPasswordAuthenticator authenticator = (IPasswordAuthenticator) this.registeredAuthProviders
                    .get(AuthenticationProvider.SEGUE);

            // User is valid and authenticated locally, proceed with reset
            // Generate token
            String token = authenticator.createPasswordResetTokenForUser(userDO);
            log.info(String.format("Sending password reset message to %s", userDO.getEmail()));

            Map<String, Object> emailValues = ImmutableMap.of("resetURL",
                    String.format("https://%s/resetpassword/%s",
                            properties.getProperty(HOST_NAME), token));

            if (this.database.hasALinkedAccount(userDO)
                    && !authenticator.hasPasswordRegistered(userDO)) {
                // User is not authenticated locally - allow them to reset their password but tell them about providers
                this.sendFederatedAuthenticatorResetMessage(userDO, userAsDTO, emailValues);
            } else {
                this.emailManager.sendTemplatedEmailToUser(userAsDTO,
                        emailManager.getEmailTemplateDTO("email-template-password-reset"),
                        emailValues, EmailType.SYSTEM);
            }

        } catch (ContentManagerException e) {
            log.error("ContentManagerException " + e.getMessage());
        } catch (NoCredentialsAvailableException e) {
            log.error("Unable to find user or credentials " + e.getMessage());
        }
    }
    
    /**
     * This method will use a unique password reset token to set a new password.
     *
     * @param token
     *            - the password reset token
     * @param newPassword
     *            - New password to set in plain text
     * @return the user which has had the password reset.
     * @throws InvalidTokenException
     *             - If the token provided is invalid.
     * @throws InvalidPasswordException
     *             - If the password provided is invalid.
     * @throws SegueDatabaseException
     *             - If there is an internal database error.
     */
    public RegisteredUser resetPassword(final String token, final String newPassword)
            throws InvalidTokenException, InvalidPasswordException, SegueDatabaseException {
        // Ensure new password is valid

        if (null == newPassword || newPassword.isEmpty()) {
            throw new InvalidPasswordException("Empty passwords are not allowed if using local authentication.");
        }

        if (newPassword.length() < 6) {
            throw new InvalidPasswordException("Password must be at least 6 characters in length.");
        }

        IPasswordAuthenticator authenticator = (IPasswordAuthenticator) this.registeredAuthProviders
                .get(AuthenticationProvider.SEGUE);

        // Ensure reset token is valid
        RegisteredUser user = authenticator.getRegisteredUserByToken(token);
        if (null == user) {
            throw new InvalidTokenException();
        }

        // Set user's password
        authenticator.setOrChangeUsersPassword(user, newPassword);
        return user;
    }
    
    /**
     * This method will send a message to a user explaining that they only use a federated authenticator.
     *
     * @param user
     *            - a user with the givenName, email and token fields set
     * @param userAsDTO
     *            - A user DTO object sanitised so that we can send it to the email manager.
     * @param additionalEmailValues
     *            - Additional email values to find and replace including any password reset urls.
     * @throws CommunicationException
     *             - if a fault occurred whilst sending the communique
     * @throws SegueDatabaseException
     *             - If there is an internal database error.
     */
    private void sendFederatedAuthenticatorResetMessage(final RegisteredUser user, final RegisteredUserDTO userAsDTO, final Map<String, Object> additionalEmailValues)
            throws CommunicationException,
            SegueDatabaseException {
        Validate.notNull(user);
        
        // Get the user's federated authenticators
        List<AuthenticationProvider> providers = this.database.getAuthenticationProvidersByUser(user);
        List<String> providerNames = new ArrayList<>();
        for (AuthenticationProvider provider : providers) {
            IAuthenticator authenticator = this.registeredAuthProviders.get(provider);
            if (!(authenticator instanceof IFederatedAuthenticator)) {
                continue;
            }

            String providerName = provider.name().toLowerCase();
            providerName = providerName.substring(0, 1).toUpperCase() + providerName.substring(1);
            providerNames.add(providerName);
        }

        String providersString;
        if (providerNames.size() == 1) {
            providersString = providerNames.get(0);
        } else {
            StringBuilder providersBuilder = new StringBuilder();
            for (int i = 0; i < providerNames.size(); i++) {
                if (i == providerNames.size() - 1) {
                    providersBuilder.append(" and ");
                } else if (i > 1) {
                    providersBuilder.append(", ");
                }
                providersBuilder.append(providerNames.get(i));
            }
            providersString = providersBuilder.toString();
        }

        String providerWord = "provider";
        if (providerNames.size() > 1) {
            providerWord += "s";
        }

        try {
            Map<String, Object> emailTokens = ImmutableMap.of("providerString", providersString,
                    "providerWord", providerWord);
            emailTokens.putAll(additionalEmailValues);

            emailManager.sendTemplatedEmailToUser(userAsDTO,
                    emailManager.getEmailTemplateDTO("email-template-federated-password-reset"),
                    emailTokens, EmailType.SYSTEM);

        } catch (ContentManagerException contentException) {
            log.error(String.format("Error sending federated email verification message - %s", 
                            contentException.getMessage()));
        }
    }
    
    /**
     * This method is an oauth2 specific method which will ultimately provide an internal reference number that the
     * oauth2 provider can use to lookup the information of the user who has just authenticated.
     * 
     * @param oauthProvider
     *            - The provider to authenticate against.
     * @param request
     *            - The request that will contain session information.
     * @return an internal reference number that will allow retrieval of the users information from the provider.
     * @throws AuthenticationCodeException
     *             - possible authentication code issues.
     * @throws IOException
     *             - error reading from client key?
     * @throws CodeExchangeException
     *             - exception whilst exchanging codes
     * @throws NoUserException
     *             - cannot find the user requested
     * @throws CrossSiteRequestForgeryException
     *             - Unable to guarantee no CSRF
     */
    private String getOauthInternalRefCode(final IOAuthAuthenticator oauthProvider, final HttpServletRequest request)
            throws AuthenticationCodeException, IOException, CodeExchangeException, NoUserException,
            CrossSiteRequestForgeryException {
        // verify there is no cross site request forgery going on.
        if (request.getQueryString() == null || !ensureNoCSRF(request, oauthProvider)) {
            throw new CrossSiteRequestForgeryException("CSRF check failed");
        }

        // this will have our authorization code within it.
        StringBuffer fullUrlBuf = request.getRequestURL();
        fullUrlBuf.append('?').append(request.getQueryString());

        // extract auth code from string buffer
        String authCode = oauthProvider.extractAuthCode(fullUrlBuf.toString());

        if (authCode != null) {
            String internalReference = oauthProvider.exchangeCode(authCode);
            return internalReference;
        } else {
            throw new AuthenticationCodeException("User denied access to our app.");
        }
    }
    

    /**
     * Verify with the request that there is no CSRF violation.
     * 
     * @param request
     *            - http request to verify there is no CSRF
     * @param oauthProvider
     *            -
     * @return true if we are happy , false if we think a violation has occurred.
     * @throws CrossSiteRequestForgeryException
     *             - if we suspect cross site request forgery.
     */
    private boolean ensureNoCSRF(final HttpServletRequest request, final IOAuthAuthenticator oauthProvider)
            throws CrossSiteRequestForgeryException {
        Validate.notNull(request);

        String key;
        if (oauthProvider instanceof IOAuth2Authenticator) {
            key = STATE_PARAM_NAME;
        } else if (oauthProvider instanceof IOAuth1Authenticator) {
            key = OAUTH_TOKEN_PARAM_NAME;
        } else {
            throw new CrossSiteRequestForgeryException("Provider not recognized.");
        }

        // to deal with cross site request forgery
        String csrfTokenFromUser = (String) request.getSession().getAttribute(key);
        String csrfTokenFromProvider = request.getParameter(key);

        if (null == csrfTokenFromUser || null == csrfTokenFromProvider
                || !csrfTokenFromUser.equals(csrfTokenFromProvider)) {
            log.error("Invalid state parameter - Provider said: " + request.getParameter(STATE_PARAM_NAME)
                    + " Session said: " + request.getSession().getAttribute(STATE_PARAM_NAME));
            return false;
        } else {
            log.debug("State parameter matches - Provider said: " + request.getParameter(STATE_PARAM_NAME)
                    + " Session said: " + request.getSession().getAttribute(STATE_PARAM_NAME));
            return true;
        }
    }
    
    /**
     * Create a session and attach it to the request provided.
     * 
     * @param request
     *            to enable access to anonymous user information.
     * @param response
     *            to store the session in our own segue cookie.
     * @param user
     *            account to associate the session with.
     */
    private void createSession(final HttpServletRequest request, final HttpServletResponse response,
            final RegisteredUser user) {
        Validate.notNull(response);
        Validate.notNull(user);
        Validate.notNull(user.getId());
        SimpleDateFormat sessionDateFormat = new SimpleDateFormat(DEFAULT_DATE_FORMAT);
        Integer sessionExpiryTimeInSeconds = Integer.parseInt(properties.getProperty(SESSION_EXPIRY_SECONDS));

        String userId = user.getId().toString();
        String hmacKey = properties.getProperty(HMAC_SALT);

        try {
            String currentDate = sessionDateFormat.format(new Date());
            String sessionHMAC = this.calculateSessionHMAC(hmacKey, userId, currentDate);

            Map<String, String> sessionInformation = ImmutableMap.of(SESSION_USER_ID, userId, DATE_SIGNED,
                    currentDate, HMAC, sessionHMAC);

            Cookie authCookie = new Cookie(SEGUE_AUTH_COOKIE,
                    serializationMapper.writeValueAsString(sessionInformation));
            authCookie.setMaxAge(sessionExpiryTimeInSeconds);
            authCookie.setPath("/");
            authCookie.setHttpOnly(true);

            response.addCookie(authCookie);
            
        } catch (JsonProcessingException e1) {
            log.error("Unable to save cookie.", e1);
        }
    }
    
    
    /**
     * Executes checks on the users sessions to ensure it is valid
     * 
     * Checks include verifying the HMAC and the session creation date.
     * 
     * @param sessionInformation
     *            - map containing session information retrieved from the cookie.
     * @return true if it is still valid, false if not.
     */
    private boolean isValidUsersSession(final Map<String, String> sessionInformation) {
        Validate.notNull(sessionInformation);

        Integer sessionExpiryTimeInSeconds = Integer.parseInt(properties.getProperty(SESSION_EXPIRY_SECONDS));

        SimpleDateFormat sessionDateFormat = new SimpleDateFormat(DEFAULT_DATE_FORMAT);

        String hmacKey = properties.getProperty(HMAC_SALT);

        String userId = sessionInformation.get(SESSION_USER_ID);
        String sessionCreationDate = sessionInformation.get(DATE_SIGNED);
        String sessionHMAC = sessionInformation.get(HMAC);

        String ourHMAC = this.calculateSessionHMAC(hmacKey, userId, sessionCreationDate);

        if (null == userId) {
            log.debug("No session set so not validating user identity.");
            return false;
        }

        // check it hasn't expired
        Calendar sessionExpiryDate = Calendar.getInstance();
        try {
            sessionExpiryDate.setTime(sessionDateFormat.parse(sessionCreationDate));
            sessionExpiryDate.add(Calendar.SECOND, sessionExpiryTimeInSeconds);

            if (new Date().after(sessionExpiryDate.getTime())) {
                log.debug("Session expired");
                return false;
            }
        } catch (ParseException e) {
            return false;
        }

        // check no one has tampered with the session.
        if (ourHMAC.equals(sessionHMAC)) {
            log.debug("Valid user session continuing...");
            return true;
        } else {
            log.debug("Invalid HMAC detected for user id " + userId);
            return false;
        }
    }
    
    /**
     * Calculate the session HMAC value based on the properties of interest.
     * 
     * @param key
     *            - secret key.
     * @param userId
     *            - User Id
     * @param currentDate
     *            - Current date
     * @return HMAC signature.
     */
    private String calculateSessionHMAC(final String key, final String userId, final String currentDate) {
        return UserAuthenticationManager.calculateHMAC(key, userId + "|" + currentDate);
    }
    
    /**
     * This method will extract the segue session information from a given request.
     * 
     * @param request
     *            - possibly containing a segue cookie.
     * @return The segue session information (unchecked or validated)
     * @throws IOException
     *             - problem parsing session information.
     * @throws InvalidSessionException
     *             - if there is no session set or if it is not valid.
     */
    private Map<String, String> getSegueSessionFromRequest(final HttpServletRequest request) throws IOException,
            InvalidSessionException {
        Cookie segueAuthCookie = null;
        if (request.getCookies() == null) {
            throw new InvalidSessionException("There are no cookies set.");
        }

        for (Cookie c : request.getCookies()) {
            if (c.getName().equals(SEGUE_AUTH_COOKIE)) {
                segueAuthCookie = c;
            }
        }

        if (null == segueAuthCookie) {
            throw new InvalidSessionException("There are no cookies set.");
        }

        @SuppressWarnings("unchecked")
        Map<String, String> sessionInformation = this.serializationMapper.readValue(segueAuthCookie.getValue(),
                HashMap.class);

        return sessionInformation;
    }
    
    /**
     * Generate an HMAC using a key and the data to sign.
     * 
     * @param key
     *            - HMAC key for signing
     * @param dataToSign
     *            - data to be signed
     * @return HMAC - Unique HMAC.
     */
    public static String calculateHMAC(final String key, final String dataToSign) {
        Validate.notEmpty(key, "Signing key cannot be blank.");
        Validate.notEmpty(dataToSign, "Data to sign cannot be blank.");

        try {
            SecretKeySpec signingKey = new SecretKeySpec(key.getBytes(), HMAC_SHA_ALGORITHM);
            Mac mac = Mac.getInstance(HMAC_SHA_ALGORITHM);
            mac.init(signingKey);

            byte[] rawHmac = mac.doFinal(dataToSign.getBytes());

            String result = new String(Base64.encodeBase64(rawHmac));
            return result;
        } catch (GeneralSecurityException e) {
            log.warn("Unexpected error while creating hash: " + e.getMessage(), e);
            throw new IllegalArgumentException();
        }
    }
}