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

import java.util.List;
import java.util.UUID;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.ac.cam.cl.dtg.segue.dao.SegueDatabaseException;
import uk.ac.cam.cl.dtg.segue.dao.associations.InvalidUserAssociationTokenException;
import uk.ac.cam.cl.dtg.segue.dao.associations.UserGroupNotFoundException;
import uk.ac.cam.cl.dtg.segue.dao.associations.IAssociationDataManager;
import uk.ac.cam.cl.dtg.segue.dao.associations.UserAssociationException;
import uk.ac.cam.cl.dtg.segue.dos.AssociationToken;
import uk.ac.cam.cl.dtg.segue.dos.UserAssociation;
import uk.ac.cam.cl.dtg.segue.dos.UserGroupDO;
import uk.ac.cam.cl.dtg.segue.dto.users.RegisteredUserDTO;

import com.google.inject.Inject;

/**
 * UserAssociationManager Responsible for managing user associations, groups and
 * permissions for one user to grant data view rights to another.
 */
public class UserAssociationManager {
	private final IAssociationDataManager associationDatabase;

	private final int tokenLength = 5;
	
	private static final Logger log = LoggerFactory.getLogger(UserAssociationManager.class);

	private final GroupManager userGroupManager;

	/**
	 * UserAssociationManager.
	 * @param associationDatabase
	 *            - IAssociationDataManager providing access to the database.
	 * @param userGroupManager
	 *            - IAssociationDataManager providing access to the database.
	 */
	@Inject
	public UserAssociationManager(final IAssociationDataManager associationDatabase,
			final GroupManager userGroupManager) {
		this.associationDatabase = associationDatabase;
		this.userGroupManager = userGroupManager;
	}

	/**
	 * generate token that other users can use to grant access to their data.
	 * 
	 * @param registeredUser
	 *            - the user who will ultimately receive access to someone
	 *            else's data.
	 * @param associatedGroupId
	 *            - Group id to add user to
	 * @return AssociationToken - that allows another user to grant permission
	 *         to the owner of the token.
	 * @throws SegueDatabaseException
	 *             - If an error occurred while interacting with the database.
	 * @throws UserGroupNotFoundException - if the group specified does not exist. 
	 */
	public AssociationToken getAssociationToken(final RegisteredUserDTO registeredUser,
			final String associatedGroupId) throws SegueDatabaseException, UserGroupNotFoundException {
		Validate.notNull(registeredUser);
		
		if (associatedGroupId != null) {
			if (!userGroupManager.isValidGroup(associatedGroupId)) {
				throw new UserGroupNotFoundException("Group not found: " + associatedGroupId);
			}

			AssociationToken groupToken = this.associationDatabase.getAssociationTokenByGroupId(associatedGroupId);
			if (groupToken != null) {
				return groupToken;
			}
		}
		
		// create some kind of random token
		String token = new String(Base64.encodeBase64(UUID.randomUUID().toString().getBytes())).replace("=",
				"").substring(0, tokenLength);

		AssociationToken associationToken = new AssociationToken(token, registeredUser.getDbId(),
				associatedGroupId);
		
		return associationDatabase.saveAssociationToken(associationToken);
	}
	
	/**
	 * getAssociations.
	 * @param user to find associations for.
	 * @return List of all of their associations.
	 */
	public List<UserAssociation> getAssociations(final RegisteredUserDTO user) {
		return associationDatabase.getUserAssociations(user.getDbId());
	}

	/**
	 * createAssociationWithToken.
	 * 
	 * @param token
	 *            - The token which links to a user (receiving permission) and
	 *            possibly a group.
	 * @param userGrantingPermission
	 *            - the user who wishes to grant permissions to another.
	 * @throws SegueDatabaseException
	 *             - If an error occurred while interacting with the database.
	 * @throws UserAssociationException
	 *             - if we cannot create the association because it is invalid.
	 * @throws InvalidUserAssociationTokenException - If the token provided is invalid.
	 */
	public void createAssociationWithToken(final String token, final RegisteredUserDTO userGrantingPermission)
		throws SegueDatabaseException, UserAssociationException, InvalidUserAssociationTokenException {
		Validate.notBlank(token);
		Validate.notNull(userGrantingPermission);

		AssociationToken lookedupToken = associationDatabase.lookupAssociationToken(token);
		
		if (null == lookedupToken) {
			throw new InvalidUserAssociationTokenException("The group token provided does not exist or is invalid.");
		}
		
		if (associationDatabase.hasValidAssociation(lookedupToken.getOwnerUserId(), userGrantingPermission.getDbId())) {
			throw new UserAssociationException("Association already exists.");
		}

		associationDatabase.createAssociation(lookedupToken, userGrantingPermission.getDbId());
		UserGroupDO group = userGroupManager.getGroupById(lookedupToken.getGroupId());
		
		if (lookedupToken.getGroupId() != null) {
			userGroupManager.addUserToGroup(group, userGrantingPermission);
			log.info(String.format("Adding User: %s to Group: %s", userGrantingPermission.getDbId(),
					lookedupToken.getGroupId()));
		}
	}

	/**
	 * Revoke user access.
	 * 
	 * @param ownerUser
	 *            - the user who owns the data
	 * @param userToRevoke
	 *            - the user to revoke access too.
	 * @throws SegueDatabaseException
	 *             - If there is a database issue whilst fulfilling the request.
	 */
	public void revokeAssociation(final RegisteredUserDTO ownerUser, final RegisteredUserDTO userToRevoke)
		throws SegueDatabaseException {
		Validate.notNull(ownerUser);
		Validate.notNull(userToRevoke);
		
		associationDatabase.deleteAssociation(ownerUser.getDbId(), userToRevoke.getDbId());
	}
}